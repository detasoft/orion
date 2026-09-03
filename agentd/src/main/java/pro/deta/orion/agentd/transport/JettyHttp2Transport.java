package pro.deta.orion.agentd.transport;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolDecoder;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SequenceDecodeIssue;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionId;

/** Jetty low-level HTTP/2 TLS transport with reusable generation-scoped connections. */
public final class JettyHttp2Transport implements AgentTransport {
    private static final System.Logger LOGGER = System.getLogger(JettyHttp2Transport.class.getName());
    private final URI endpoint;
    private final SslContextFactory.Client tls;
    private final HTTP2Client client;
    private final AgentProtocolLimits limits;
    private final OutboundQueues<Pending> outbound;
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agentd-http2-callbacks");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<AgentMessage>> controlReceivers = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<SessionId, AgentMessage>> sessionReceivers = new CopyOnWriteArrayList<>();
    private final List<Consumer<TransportSignal>> signals = new CopyOnWriteArrayList<>();
    private final Map<SessionId, Pending> activeSessions = new ConcurrentHashMap<>();
    private Generation current;
    private Pending activeControl;
    private long sequence;
    private boolean managesTls;
    private boolean closed;

    public JettyHttp2Transport(URI endpoint, SslContextFactory.Client tls, AgentProtocolLimits limits,
                               int controlCapacity, int sessionCapacity) {
        this(endpoint, tls, new HTTP2Client(), limits, controlCapacity, sessionCapacity);
    }

    JettyHttp2Transport(URI endpoint, SslContextFactory.Client tls, HTTP2Client client,
                        AgentProtocolLimits limits, int controlCapacity, int sessionCapacity) {
        this.endpoint = https(endpoint);
        this.tls = Objects.requireNonNull(tls, "tls");
        this.client = Objects.requireNonNull(client, "client");
        this.limits = Objects.requireNonNull(limits, "limits");
        outbound = new OutboundQueues<>(controlCapacity, sessionCapacity);
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        client.setProtocols(List.of("h2"));
        client.setUseALPN(true);
        client.setConnectTimeout(5_000);
    }

    @Override
    public CompletionStage<Void> connect() {
        Generation generation;
        Generation previous;
        CompletableFuture<Session> connection;
        Throwable replacement = new IllegalStateException("connection replaced");
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
            }
            previous = current;
            if (previous != null) {
                current = null;
                previous.decoder.reset();
                previous.ready.completeExceptionally(replacement);
                clearSessions(previous, replacement);
                failActive(replacement);
                failQueued(replacement);
            }
            generation = new Generation(++sequence, limits);
            current = generation;
        }
        closeSession(previous == null ? null : previous.session, ErrorCode.NO_ERROR.code, "connection replaced");
        try {
            startInfrastructure(generation);
        } catch (Exception failure) {
            failed(generation, TransportSignal.Kind.DISCONNECTED, failure);
            return generation.ready;
        }
        int port = endpoint.getPort() < 0 ? 443 : endpoint.getPort();
        synchronized (this) {
            if (!current(generation)) {
                return generation.ready;
            }
            try {
                connection = client.connect(tls, new InetSocketAddress(endpoint.getHost(), port),
                        new SessionEvents(generation));
            } catch (RuntimeException failure) {
                failed(generation, TransportSignal.Kind.DISCONNECTED, failure);
                return generation.ready;
            }
        }
        connection.whenComplete((session, failure) -> connected(generation, session, failure));
        return generation.ready;
    }

    @Override
    public CompletionStage<Void> sendControlCbor(byte[] item) {
        return sendRaw(null, item, true);
    }

    @Override
    public CompletionStage<Void> sendSessionCbor(SessionId id, byte[] item) {
        return sendRaw(Objects.requireNonNull(id, "sessionId"), item, false);
    }

    @Override
    public CompletionStage<Void> openSession(SessionId id, SessionStreamRequest request) {
        Objects.requireNonNull(id, "sessionId");
        Objects.requireNonNull(request, "request");
        Generation generation;
        SessionState state = new SessionState(limits);
        CompletableFuture<Stream> opening;
        synchronized (this) {
            generation = current;
            if (generation == null || !generation.accepted) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("control stream is not connected"));
            }
            if (generation.sessions.putIfAbsent(id, state) != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("session stream is already open"));
            }
            try {
                opening = generation.session.newStream(request.headers(id),
                        new SessionStreamEvents(generation, id, state));
            } catch (RuntimeException failure) {
                sessionFailed(generation, id, state, null, TransportSignal.Kind.DISCONNECTED, failure);
                return state.ready;
            }
        }
        opening.whenComplete((stream, failure) -> sessionStreamOpened(generation, id, state, stream, failure));
        return state.ready;
    }

    @Override
    public void onControlMessage(Consumer<AgentMessage> receiver) {
        controlReceivers.add(Objects.requireNonNull(receiver, "receiver"));
    }

    @Override
    public void onSessionMessage(BiConsumer<SessionId, AgentMessage> receiver) {
        sessionReceivers.add(Objects.requireNonNull(receiver, "receiver"));
    }

    @Override
    public void onSignal(Consumer<TransportSignal> receiver) {
        signals.add(Objects.requireNonNull(receiver, "receiver"));
    }

    @Override
    public void close() {
        Generation generation;
        Throwable failure = new IllegalStateException("transport is closed");
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            generation = current;
            current = null;
            if (generation != null) {
                generation.decoder.reset();
                generation.ready.completeExceptionally(failure);
                clearSessions(generation, failure);
            }
            failActive(failure);
            failQueued(failure);
        }
        closeSession(generation == null ? null : generation.session, ErrorCode.NO_ERROR.code, "transport closed");
        try {
            client.stop();
        } catch (Exception ignored) {
            // Closing is best effort; all application futures have already been completed.
        }
        if (managesTls && !tls.isStopped()) {
            try {
                tls.stop();
            } catch (Exception ignored) {
                // Closing is best effort; the factory remains owned by this closed transport.
            }
        }
        emit(new TransportSignal(TransportSignal.Kind.CLOSED, null));
        callbacks.shutdown();
    }

    private void connected(Generation generation, Session session, Throwable failure) {
        if (failure != null) {
            failed(generation, TransportSignal.Kind.DISCONNECTED, unwrap(failure));
            return;
        }
        CompletableFuture<Stream> opening;
        synchronized (this) {
            if (!current(generation)) {
                closeSession(session, ErrorCode.NO_ERROR.code, "stale connection");
                return;
            }
            generation.session = session;
            try {
                opening = session.newStream(controlHeaders(), new StreamEvents(generation));
            } catch (RuntimeException error) {
                failed(generation, TransportSignal.Kind.DISCONNECTED, error);
                return;
            }
        }
        opening.whenComplete((stream, error) -> controlStreamOpened(generation, stream, error));
    }

    private void controlStreamOpened(Generation generation, Stream stream, Throwable failure) {
        if (failure != null) {
            failed(generation, TransportSignal.Kind.DISCONNECTED, unwrap(failure));
            return;
        }
        synchronized (this) {
            if (!current(generation)) {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                return;
            }
            generation.stream = stream;
            drain();
        }
    }

    private synchronized void startInfrastructure(Generation generation) throws Exception {
        if (!current(generation)) {
            return;
        }
        if (!tls.isStarted()) {
            managesTls = true;
            tls.start();
        }
        if (!client.isStarted()) {
            client.start();
        }
    }

    private void sessionStreamOpened(Generation generation, SessionId id, SessionState state,
                                     Stream stream, Throwable failure) {
        if (failure != null) {
            sessionFailed(generation, id, state, null, TransportSignal.Kind.DISCONNECTED, unwrap(failure));
            return;
        }
        synchronized (this) {
            if (!current(generation) || generation.sessions.get(id) != state) {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                state.ready.completeExceptionally(new IllegalStateException("connection was replaced"));
                return;
            }
            if (state.stream != null && state.stream != stream) {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                state.ready.completeExceptionally(new IllegalStateException("session stream is already open"));
                return;
            }
            state.stream = stream;
        }
    }

    private CompletionStage<Void> sendRaw(SessionId id, byte[] item, boolean control) {
        if (item == null || item.length == 0 || item.length > limits.maxFrameBytes()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("CBOR item exceeds bound"));
        }
        Pending pending = new Pending(id, item.clone());
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
            }
            if (!(control ? outbound.offerControl(pending) : outbound.offerSession(id, pending))) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("outbound transport queue is full"));
            }
        }
        drain();
        return pending.result;
    }

    private synchronized void drain() {
        if (closed || current == null || !current.accepted || current.stream == null) {
            return;
        }
        Generation generation = current;
        if (activeControl == null) {
            OutboundQueues.Entry<Pending> control = outbound.pollControl();
            if (control != null) {
                activeControl = control.value();
                write(generation, generation.stream, activeControl);
            }
        }
        OutboundQueues.Entry<Pending> session;
        while ((session = outbound.pollSession(id -> sessionEligible(generation, id))) != null) {
            Pending pending = session.value();
            SessionState state = generation.sessions.get(pending.sessionId);
            if (state == null || state.stream == null) {
                pending.result.completeExceptionally(new IllegalStateException("session stream is not open"));
                continue;
            }
            activeSessions.put(pending.sessionId, pending);
            write(generation, state.stream, pending);
        }
    }

    private boolean sessionEligible(Generation generation, SessionId id) {
        if (activeSessions.containsKey(id)) {
            return false;
        }
        SessionState state = generation.sessions.get(id);
        return state == null || state.accepted;
    }

    private void write(Generation generation, Stream stream, Pending pending) {
        stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(pending.frame), false), Callback.from(
                () -> complete(generation, pending, null),
                error -> complete(generation, pending, error)));
    }

    private synchronized void complete(Generation generation, Pending pending, Throwable error) {
        boolean active = pending.sessionId == null
                ? activeControl == pending : activeSessions.remove(pending.sessionId, pending);
        if (!active) {
            return;
        }
        if (pending.sessionId == null) {
            activeControl = null;
        }
        if (!current(generation)) {
            pending.result.completeExceptionally(new IllegalStateException("connection was replaced"));
            return;
        }
        if (error == null) {
            pending.result.complete(null);
        } else {
            pending.result.completeExceptionally(error);
        }
        drain();
    }

    private void failed(Generation generation, TransportSignal.Kind kind, Throwable error) {
        Throwable cause = error == null ? new IllegalStateException(kind.name()) : error;
        Session session;
        synchronized (this) {
            if (!current(generation)) {
                return;
            }
            current = null;
            generation.decoder.reset();
            generation.ready.completeExceptionally(cause);
            clearSessions(generation, cause);
            failActive(cause);
            failQueued(cause);
            session = generation.session;
        }
        closeSession(session, ErrorCode.CANCEL_STREAM_ERROR.code, kind.name());
        emit(new TransportSignal(kind, error));
    }

    private void sessionFailed(Generation generation, SessionId id, SessionState state, Stream stream,
                               TransportSignal.Kind kind, Throwable failure) {
        List<OutboundQueues.Entry<Pending>> queued;
        Pending activeSession = null;
        synchronized (this) {
            if (!current(generation) || generation.sessions.get(id) != state
                    || (stream != null && state.stream != null && state.stream != stream)) {
                return;
            }
            generation.sessions.remove(id, state);
            state.decoder.reset();
            activeSession = activeSessions.remove(id);
            queued = outbound.drainSession(id);
        }
        Throwable cause = failure == null ? new IllegalStateException(kind.name()) : failure;
        state.ready.completeExceptionally(cause);
        if (activeSession != null) {
            activeSession.result.completeExceptionally(cause);
        }
        for (OutboundQueues.Entry<Pending> entry : queued) {
            entry.value().result.completeExceptionally(cause);
        }
        emit(new TransportSignal(kind, id, failure));
        drain();
    }

    private void clearSessions(Generation generation, Throwable failure) {
        for (SessionState state : generation.sessions.values()) {
            state.decoder.reset();
            state.ready.completeExceptionally(failure);
        }
        generation.sessions.clear();
    }

    private void failActive(Throwable error) {
        if (activeControl != null) {
            activeControl.result.completeExceptionally(error);
            activeControl = null;
        }
        for (Pending pending : activeSessions.values()) {
            pending.result.completeExceptionally(error);
        }
        activeSessions.clear();
    }

    private void failQueued(Throwable error) {
        for (OutboundQueues.Entry<Pending> entry : outbound.drain()) {
            entry.value().result.completeExceptionally(error);
        }
    }

    private HeadersFrame controlHeaders() {
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from(endpoint.resolve("/agent/control")),
                HttpVersion.HTTP_2, HttpFields.EMPTY);
        return new HeadersFrame(request, null, false);
    }

    private void receiveControl(Generation generation, ByteBuffer data, boolean endStream) {
        if (!current(generation) || generation.terminalAccepted) {
            return;
        }
        SequenceDecodeResult<AgentMessage> result = generation.decoder.accept(data);
        SequenceDecodeIssue.Terminal terminal = result.terminalIssue().orElse(null);
        if (terminal == null && endStream) {
            terminal = generation.decoder.finish().terminalIssue().orElse(null);
        }
        Throwable endFailure = terminal == null && endStream
                ? new IllegalStateException("control stream ended")
                : terminal == null ? null : terminal.exception();
        if (endFailure != null) {
            generation.terminalAccepted = true;
        }
        executeCallback(() -> deliverControl(generation, result, endFailure));
    }

    private void receiveSession(Generation generation, SessionId id, SessionState state,
                                Stream stream, ByteBuffer data, boolean endStream) {
        if (!sessionCurrent(generation, id, state) || state.terminalAccepted) {
            return;
        }
        SequenceDecodeResult<AgentMessage> result = state.decoder.accept(data);
        SequenceDecodeIssue.Terminal terminal = result.terminalIssue().orElse(null);
        if (terminal == null && endStream) {
            terminal = state.decoder.finish().terminalIssue().orElse(null);
        }
        Throwable endFailure = terminal == null && endStream
                ? new IllegalStateException("session stream ended")
                : terminal == null ? null : terminal.exception();
        if (endFailure != null) {
            state.terminalAccepted = true;
        }
        SequenceDecodeIssue.Terminal finalTerminal = terminal;
        executeCallback(() -> deliverSession(
                generation, id, state, stream, result, finalTerminal, endFailure));
    }

    private void deliverControl(
            Generation generation,
            SequenceDecodeResult<AgentMessage> result,
            Throwable endFailure
    ) {
        if (!generationDeliveryCurrent(generation)) {
            return;
        }
        for (SequenceDecodeResult.Outcome<AgentMessage> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded) {
                for (Consumer<AgentMessage> receiver : controlReceivers) {
                    receiver.accept(decoded.value());
                }
                if (!generationDeliveryCurrent(generation)) {
                    return;
                }
            } else if (outcome instanceof SequenceDecodeResult.Rejected<AgentMessage> rejected) {
                logRecoverable("control", null, rejected.issue());
            }
        }
        if (endFailure != null) {
            failed(generation, TransportSignal.Kind.DISCONNECTED, endFailure);
        }
    }

    private void deliverSession(
            Generation generation,
            SessionId id,
            SessionState state,
            Stream stream,
            SequenceDecodeResult<AgentMessage> result,
            SequenceDecodeIssue.Terminal terminal,
            Throwable endFailure
    ) {
        if (!sessionDeliveryCurrent(generation, id, state)) {
            return;
        }
        for (SequenceDecodeResult.Outcome<AgentMessage> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded) {
                for (BiConsumer<SessionId, AgentMessage> receiver : sessionReceivers) {
                    receiver.accept(id, decoded.value());
                }
                if (!sessionDeliveryCurrent(generation, id, state)) {
                    return;
                }
            } else if (outcome instanceof SequenceDecodeResult.Rejected<AgentMessage> rejected) {
                logRecoverable("session", id, rejected.issue());
            }
        }
        if (endFailure != null) {
            if (terminal != null) {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.PROTOCOL_ERROR.code), Callback.NOOP);
            }
            sessionFailed(
                    generation, id, state, stream, TransportSignal.Kind.DISCONNECTED, endFailure);
        }
    }

    private static void logRecoverable(
            String streamKind,
            SessionId sessionId,
            SequenceDecodeIssue.Recoverable issue
    ) {
        String context = sessionId == null
                ? "stream=control"
                : "stream=" + streamKind + ", sessionId=" + sessionId.value();
        LOGGER.log(
                System.Logger.Level.ERROR,
                "Rejected Agent protocol item: {0}, reason={1}, itemLength={2}",
                context,
                issue.exception().reason(),
                issue.encodedLength());
    }

    private synchronized boolean current(Generation generation) {
        return current == generation;
    }

    private synchronized boolean sessionCurrent(Generation generation, SessionId id, SessionState state) {
        return current == generation && generation.sessions.get(id) == state;
    }

    private synchronized boolean generationDeliveryCurrent(Generation generation) {
        return !closed && generation.id == sequence;
    }

    private synchronized boolean sessionDeliveryCurrent(
            Generation generation,
            SessionId id,
            SessionState state
    ) {
        if (closed || generation.id != sequence) {
            return false;
        }
        SessionState active = generation.sessions.get(id);
        return active == null || active == state;
    }

    private void emit(TransportSignal signal) {
        executeCallback(() -> {
            for (Consumer<TransportSignal> receiver : signals) {
                receiver.accept(signal);
            }
        });
    }

    private void executeCallback(Runnable callback) {
        try {
            callbacks.execute(callback);
        } catch (RejectedExecutionException ignored) {
            // Close races may report late Jetty callbacks after the application executor has stopped.
        }
    }

    private static void closeSession(Session session, int error, String reason) {
        if (session != null && !session.isClosed()) {
            session.close(error, reason, Callback.NOOP);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause() : error;
    }

    private static URI https(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("AgentD endpoint must be an absolute HTTPS URI");
        }
        return uri;
    }

    private final class SessionEvents implements Session.Listener {
        private final Generation generation;

        private SessionEvents(Generation generation) {
            this.generation = generation;
        }

        @Override
        public void onGoAway(Session session, org.eclipse.jetty.http2.frames.GoAwayFrame frame) {
            failed(generation, TransportSignal.Kind.GO_AWAY, null);
        }

        @Override
        public void onFailure(Session session, Throwable error, Callback callback) {
            callback.succeeded();
            failed(generation, TransportSignal.Kind.DISCONNECTED, error);
        }
    }

    private final class StreamEvents implements Stream.Listener {
        private final Generation generation;

        private StreamEvents(Generation generation) {
            this.generation = generation;
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame) {
            if (!(frame.getMetaData() instanceof MetaData.Response response)
                    || response.getStatus() / 100 != 2 || frame.isEndStream()) {
                failed(generation, TransportSignal.Kind.DISCONNECTED,
                        new IllegalStateException("control stream rejected"));
                return;
            }
            synchronized (JettyHttp2Transport.this) {
                if (!current(generation)) {
                    return;
                }
                generation.accepted = true;
                generation.ready.complete(null);
                drain();
            }
            emit(new TransportSignal(TransportSignal.Kind.CONNECTED, null));
            stream.demand();
        }

        @Override
        public void onDataAvailable(Stream stream) {
            Stream.Data data;
            while ((data = stream.readData()) != null) {
                try {
                    receiveControl(generation, data.frame().getByteBuffer(), data.frame().isEndStream());
                } finally {
                    data.release();
                }
                if (generation.terminalAccepted) {
                    break;
                }
            }
            if (current(generation) && !generation.terminalAccepted) {
                stream.demand();
            }
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback) {
            callback.succeeded();
            TransportSignal.Kind kind = frame.getError() == ErrorCode.CANCEL_STREAM_ERROR.code
                    ? TransportSignal.Kind.STREAM_RESET : TransportSignal.Kind.DISCONNECTED;
            failed(generation, kind,
                    new IllegalStateException("control stream reset with error " + frame.getError()));
        }

        @Override
        public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback) {
            callback.succeeded();
            Throwable cause = failure == null
                    ? new IllegalStateException("stream error=" + error + ", reason=" + reason) : failure;
            TransportSignal.Kind kind = error == ErrorCode.CANCEL_STREAM_ERROR.code
                    ? TransportSignal.Kind.STREAM_RESET : TransportSignal.Kind.DISCONNECTED;
            failed(generation, kind, cause);
        }

        @Override
        public void onClosed(Stream stream) {
            if (!current(generation)) {
                return;
            }
            try {
                client.getScheduler().schedule(() -> {
                    TransportSignal.Kind kind = stream.isReset()
                            ? TransportSignal.Kind.STREAM_RESET : TransportSignal.Kind.DISCONNECTED;
                    failed(generation, kind, new IllegalStateException("control stream closed"));
                }, 10, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Transport shutdown may stop the scheduler before this late close callback.
            }
        }
    }

    private final class SessionStreamEvents implements Stream.Listener {
        private final Generation generation;
        private final SessionId sessionId;
        private final SessionState state;

        private SessionStreamEvents(Generation generation, SessionId sessionId, SessionState state) {
            this.generation = generation;
            this.sessionId = sessionId;
            this.state = state;
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame) {
            if (!(frame.getMetaData() instanceof MetaData.Response response)
                    || response.getStatus() / 100 != 2 || frame.isEndStream()) {
                sessionFailed(generation, sessionId, state, stream, TransportSignal.Kind.DISCONNECTED,
                        new IllegalStateException("session stream rejected"));
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                return;
            }
            synchronized (JettyHttp2Transport.this) {
                if (!current(generation) || generation.sessions.get(sessionId) != state) {
                    return;
                }
                if (state.stream != null && state.stream != stream) {
                    return;
                }
                state.stream = stream;
                state.accepted = true;
                state.ready.complete(null);
                drain();
            }
            stream.demand();
        }

        @Override
        public void onDataAvailable(Stream stream) {
            Stream.Data data;
            while ((data = stream.readData()) != null) {
                try {
                    receiveSession(
                            generation,
                            sessionId,
                            state,
                            stream,
                            data.frame().getByteBuffer(),
                            data.frame().isEndStream());
                } finally {
                    data.release();
                }
                if (state.terminalAccepted) {
                    break;
                }
            }
            if (current(generation)
                    && generation.sessions.get(sessionId) == state
                    && !state.terminalAccepted) {
                stream.demand();
            }
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback) {
            callback.succeeded();
            TransportSignal.Kind kind = frame.getError() == ErrorCode.CANCEL_STREAM_ERROR.code
                    ? TransportSignal.Kind.STREAM_RESET : TransportSignal.Kind.DISCONNECTED;
            sessionFailed(generation, sessionId, state, stream, kind,
                    new IllegalStateException("session stream reset with error " + frame.getError()));
        }

        @Override
        public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback) {
            callback.succeeded();
            Throwable cause = failure == null
                    ? new IllegalStateException("stream error=" + error + ", reason=" + reason) : failure;
            TransportSignal.Kind kind = error == ErrorCode.CANCEL_STREAM_ERROR.code
                    ? TransportSignal.Kind.STREAM_RESET : TransportSignal.Kind.DISCONNECTED;
            sessionFailed(generation, sessionId, state, stream, kind, cause);
        }

        @Override
        public void onClosed(Stream stream) {
            if (!current(generation) || generation.sessions.get(sessionId) != state) {
                return;
            }
            try {
                client.getScheduler().schedule(() -> {
                    TransportSignal.Kind kind = stream.isReset()
                            ? TransportSignal.Kind.STREAM_RESET : TransportSignal.Kind.DISCONNECTED;
                    sessionFailed(generation, sessionId, state, stream, kind,
                            new IllegalStateException("session stream closed"));
                }, 10, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Transport shutdown may stop the scheduler before this late close callback.
            }
        }
    }

    private static final class Generation {
        private final long id;
        private final AgentProtocolDecoder decoder;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Map<SessionId, SessionState> sessions = new ConcurrentHashMap<>();
        private volatile Session session;
        private volatile Stream stream;
        private volatile boolean accepted;
        private volatile boolean terminalAccepted;

        private Generation(long id, AgentProtocolLimits limits) {
            this.id = id;
            decoder = new AgentProtocolDecoder(limits);
        }
    }

    private static final class SessionState {
        private final AgentProtocolDecoder decoder;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private volatile Stream stream;
        private volatile boolean accepted;
        private volatile boolean terminalAccepted;

        private SessionState(AgentProtocolLimits limits) {
            decoder = new AgentProtocolDecoder(limits);
        }
    }

    private static final class Pending {
        private final SessionId sessionId;
        private final byte[] frame;
        private final CompletableFuture<Void> result = new CompletableFuture<>();

        private Pending(SessionId sessionId, byte[] frame) {
            this.sessionId = sessionId;
            this.frame = frame;
        }
    }
}
