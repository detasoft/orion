package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class AgentProtocolDecoder {
    private final CborSequenceParser<AgentMessageRecord> sequence;

    public AgentProtocolDecoder(AgentProtocolLimits limits) {
        AgentProtocolLimits messageLimits = Objects.requireNonNull(limits, "limits").agentMessageLimits();
        AgentProtocolCodec codec = new AgentProtocolCodec(messageLimits);
        sequence = new CborSequenceParser<>(messageLimits, (bytes, from, to) ->
                new AgentMessageRecord(codec.decode(bytes, from, to), ProtocolBytes.copyOf(bytes, from, to)));
    }

    public SequenceDecodeResult<AgentMessageRecord> accept(ByteBuffer data) {
        return sequence.accept(data);
    }

    public SequenceDecodeResult<AgentMessageRecord> finish() {
        return sequence.finish();
    }

    public void reset() {
        sequence.reset();
    }

    public int pendingBytes() {
        return sequence.pendingBytes();
    }
}
