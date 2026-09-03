package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class SessionEventDecoder {
    private final CborSequenceParser<SessionEventRecord> sequence;

    public SessionEventDecoder(AgentProtocolLimits limits) {
        Objects.requireNonNull(limits, "limits");
        SessionEventCodec codec = new SessionEventCodec(limits);
        sequence = new CborSequenceParser<>(limits, codec::decode);
    }

    public SequenceDecodeResult<SessionEventRecord> accept(ByteBuffer data) {
        return sequence.accept(data);
    }

    public SequenceDecodeResult<SessionEventRecord> finish() {
        return sequence.finish();
    }

    public void reset() {
        sequence.reset();
    }

    public int pendingBytes() {
        return sequence.pendingBytes();
    }
}
