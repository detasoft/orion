package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.protocol.SessionReplicationDecoder;
import pro.deta.orion.agent.protocol.SessionReplicationItem;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.server.replication.SessionReplicationException;
import pro.deta.orion.agent.server.replication.SessionReplicationService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

final class JettySessionReplicationStream implements Stream.Listener {
    private final Stream stream;
    private final SessionId sessionId;
    private final AgentId agentId;
    private final SessionReplicationService replication;
    private final Executor executor;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final SessionReplicationDecoder decoder;
    private final AgentProtocolCodec codec;
    private volatile boolean opened;

    JettySessionReplicationStream(
            Stream stream,
            SessionId sessionId,
            AgentId agentId,
            SessionReplicationService replication,
            AgentProtocolLimits limits,
            Executor executor) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.replication = Objects.requireNonNull(replication, "replication");
        this.executor = Objects.requireNonNull(executor, "executor");
        decoder = new SessionReplicationDecoder(Objects.requireNonNull(limits, "limits"));
        codec = new AgentProtocolCodec(limits);
    }

    @Override
    public void onDataAvailable(Stream ignored) {
        if (terminal.get()) {
            return;
        }
        Stream.Data data;
        try {
            data = stream.readData();
        } catch (RuntimeException failure) {
            fail(ErrorCode.INTERNAL_ERROR.code);
            return;
        }
        if (data == null) {
            demand();
            return;
        }

        Work work;
        boolean protocolFailure = false;
        try {
            work = decode(data);
        } catch (ProtocolFailure failure) {
            protocolFailure = true;
            work = null;
        } finally {
            data.release();
        }
        if (protocolFailure) {
            fail(ErrorCode.PROTOCOL_ERROR.code);
            return;
        }
        if (work == null) {
            demand();
            return;
        }
        Work acceptedWork = work;
        try {
            executor.execute(() -> process(acceptedWork));
        } catch (RuntimeException failure) {
            fail(ErrorCode.INTERNAL_ERROR.code);
        }
    }

    private Work decode(Stream.Data data) throws ProtocolFailure {
        SequenceDecodeResult<SessionReplicationItem> decoded =
                decoder.accept(data.frame().getByteBuffer());
        if (decoded.terminalIssue().isPresent()) {
            throw new ProtocolFailure();
        }
        AgentMessage.SessionOpen open = null;
        List<SessionEventRecord> events = new ArrayList<>();
        for (SequenceDecodeResult.Outcome<SessionReplicationItem> outcome : decoded.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Rejected<SessionReplicationItem>) {
                throw new ProtocolFailure();
            }
            SessionReplicationItem item =
                    ((SequenceDecodeResult.Decoded<SessionReplicationItem>) outcome).value();
            if (item instanceof SessionReplicationItem.Open candidate) {
                if (opened || !sessionId.equals(candidate.message().sessionId())) {
                    throw new ProtocolFailure();
                }
                opened = true;
                open = candidate.message();
            } else if (item instanceof SessionReplicationItem.Event event && opened) {
                events.add(event.record());
            } else {
                throw new ProtocolFailure();
            }
        }
        boolean endStream = data.frame().isEndStream();
        if (endStream) {
            SequenceDecodeResult<SessionReplicationItem> finished = decoder.finish();
            if (finished.terminalIssue().isPresent() || !opened) {
                throw new ProtocolFailure();
            }
        }
        if (open == null && events.isEmpty() && !endStream) {
            return null;
        }
        return new Work(open, List.copyOf(events), endStream);
    }

    private void process(Work work) {
        if (terminal.get()) {
            return;
        }
        List<byte[]> responses = new ArrayList<>(2);
        try {
            if (work.open() != null) {
                responses.add(codec.encode(replication.open(agentId, work.open())));
            }
            if (!work.events().isEmpty()) {
                responses.add(codec.encode(replication.append(sessionId, work.events())));
            }
        } catch (SessionReplicationException failure) {
            fail(failure.kind() == SessionReplicationException.Kind.PROTOCOL
                    ? ErrorCode.PROTOCOL_ERROR.code
                    : ErrorCode.INTERNAL_ERROR.code);
            return;
        } catch (AgentProtocolException | RuntimeException failure) {
            fail(ErrorCode.INTERNAL_ERROR.code);
            return;
        }
        send(responses, 0, work.endStream());
    }

    private void send(List<byte[]> responses, int index, boolean endStream) {
        if (terminal.get()) {
            return;
        }
        if (index < responses.size()) {
            write(
                    new DataFrame(stream.getId(), ByteBuffer.wrap(responses.get(index)), false),
                    () -> send(responses, index + 1, endStream));
            return;
        }
        if (endStream) {
            write(new DataFrame(stream.getId(), ByteBuffer.allocate(0), true),
                    () -> terminal.set(true));
        } else {
            demand();
        }
    }

    private void write(DataFrame frame, Runnable succeeded) {
        try {
            stream.data(frame, Callback.from(
                    succeeded,
                    ignored -> fail(ErrorCode.INTERNAL_ERROR.code)));
        } catch (RuntimeException failure) {
            fail(ErrorCode.INTERNAL_ERROR.code);
        }
    }

    private void demand() {
        if (!terminal.get()) {
            try {
                stream.demand();
            } catch (RuntimeException failure) {
                fail(ErrorCode.INTERNAL_ERROR.code);
            }
        }
    }

    private void fail(int error) {
        if (terminal.compareAndSet(false, true)) {
            try {
                stream.reset(new ResetFrame(stream.getId(), error), Callback.NOOP);
            } catch (RuntimeException ignored) {
                // The stream is already terminal; there is no remaining recovery action.
            }
        }
    }

    void accepted(boolean requestEnded) {
        if (requestEnded) {
            fail(ErrorCode.PROTOCOL_ERROR.code);
        } else {
            demand();
        }
    }

    void admissionFailed(Throwable failure) {
        terminal.set(true);
    }

    @Override
    public void onReset(Stream ignored, ResetFrame frame, Callback callback) {
        terminal.set(true);
        callback.succeeded();
    }

    @Override
    public void onFailure(
            Stream ignored,
            int error,
            String reason,
            Throwable failure,
            Callback callback) {
        terminal.set(true);
        callback.succeeded();
    }

    @Override
    public void onClosed(Stream ignored) {
        terminal.set(true);
    }

    private record Work(
            AgentMessage.SessionOpen open,
            List<SessionEventRecord> events,
            boolean endStream) {
    }

    private static final class ProtocolFailure extends Exception {
    }
}
