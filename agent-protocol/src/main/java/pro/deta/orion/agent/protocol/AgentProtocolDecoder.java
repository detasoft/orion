package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class AgentProtocolDecoder {
    private final CborSequenceParser<AgentMessage> sequence;

    public AgentProtocolDecoder(AgentProtocolLimits limits) {
        AgentProtocolLimits messageLimits = Objects.requireNonNull(limits, "limits").agentMessageLimits();
        AgentProtocolCodec codec = new AgentProtocolCodec(messageLimits);
        sequence = new CborSequenceParser<>(messageLimits, codec::decode);
    }

    public SequenceDecodeResult<AgentMessage> accept(ByteBuffer data) {
        return sequence.accept(data);
    }

    public SequenceDecodeResult<AgentMessage> finish() {
        return sequence.finish();
    }

    public void reset() {
        sequence.reset();
    }

    public int pendingBytes() {
        return sequence.pendingBytes();
    }
}
