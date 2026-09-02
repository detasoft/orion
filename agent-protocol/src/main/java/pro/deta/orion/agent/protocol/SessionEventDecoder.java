package pro.deta.orion.agent.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SessionEventDecoder {
    private final SessionEventCodec codec;
    private final CborSequenceBuffer sequence;

    public SessionEventDecoder(AgentProtocolLimits limits) {
        Objects.requireNonNull(limits, "limits");
        codec = new SessionEventCodec(limits);
        sequence = new CborSequenceBuffer(limits);
    }

    public List<SessionEventRecord> accept(byte[] data) throws AgentProtocolException {
        List<byte[]> items = sequence.accept(data);
        List<SessionEventRecord> events = new ArrayList<>(items.size());
        for (byte[] item : items) {
            events.add(codec.decode(item));
        }
        return List.copyOf(events);
    }

    public int pendingBytes() {
        return sequence.pendingBytes();
    }
}
