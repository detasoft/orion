package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class AgentProtocolDecoder {
    private final CborSequenceParser<AgentMessage> sequence;

    public AgentProtocolDecoder(AgentProtocolLimits limits) {
        Objects.requireNonNull(limits, "limits");
        AgentProtocolCodec codec = new AgentProtocolCodec(limits);
        sequence = new CborSequenceParser<>(limits, codec::decode);
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
