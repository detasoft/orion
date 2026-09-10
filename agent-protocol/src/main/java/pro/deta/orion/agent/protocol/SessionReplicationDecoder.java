package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SessionReplicationDecoder {
    private final AgentProtocolCodec messages;
    private final SessionEventCodec events;
    private final CborSequenceParser<SessionReplicationItem> sequence;
    private boolean opened;
    private boolean missingOpenAtEnd;

    public SessionReplicationDecoder(AgentProtocolLimits limits) {
        Objects.requireNonNull(limits, "limits");
        messages = new AgentProtocolCodec(limits);
        events = new SessionEventCodec(limits);
        sequence = new CborSequenceParser<>(limits, this::decode);
    }

    public SequenceDecodeResult<SessionReplicationItem> accept(ByteBuffer data) {
        requireUsable();
        return sequence.accept(data);
    }

    public SequenceDecodeResult<SessionReplicationItem> finish() {
        requireUsable();
        if (!opened && sequence.pendingBytes() == 0) {
            missingOpenAtEnd = true;
            AgentProtocolException exception = new AgentProtocolException(
                    AgentProtocolException.Reason.INVALID_FIELD,
                    "Replication stream must start with SESSION_OPEN");
            return new SequenceDecodeResult<>(
                    List.of(),
                    Optional.of(new SequenceDecodeIssue.Terminal(exception, 0)));
        }
        return sequence.finish();
    }

    public void reset() {
        sequence.reset();
        opened = false;
        missingOpenAtEnd = false;
    }

    public int pendingBytes() {
        return sequence.pendingBytes();
    }

    public boolean opened() {
        return opened;
    }

    private void requireUsable() {
        if (missingOpenAtEnd) {
            throw new IllegalStateException("Replication decoder must be reset after a terminal failure");
        }
    }

    private SessionReplicationItem decode(byte[] bytes, int from, int to)
            throws AgentProtocolException {
        if (!opened) {
            AgentMessage message = messages.decode(bytes, from, to);
            if (!(message instanceof AgentMessage.SessionOpen open)) {
                throw new AgentProtocolException(
                        AgentProtocolException.Reason.INVALID_FIELD,
                        "Replication stream must start with SESSION_OPEN");
            }
            opened = true;
            return new SessionReplicationItem.Open(open);
        }
        return new SessionReplicationItem.Event(events.decode(bytes, from, to));
    }
}
