package pro.deta.orion.agent.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentProtocolDecoder {
    private final AgentProtocolCodec codec;
    private final CborSequenceBuffer sequence;

    public AgentProtocolDecoder(AgentProtocolLimits limits) {
        Objects.requireNonNull(limits, "limits");
        codec = new AgentProtocolCodec(limits);
        sequence = new CborSequenceBuffer(limits);
    }

    public List<AgentMessage> accept(byte[] data) throws AgentProtocolException {
        List<byte[]> items = sequence.accept(data);
        List<AgentMessage> messages = new ArrayList<>(items.size());
        for (byte[] item : items) {
            messages.add(codec.decode(item));
        }
        return List.copyOf(messages);
    }

    public int pendingBytes() {
        return sequence.pendingBytes();
    }
}
