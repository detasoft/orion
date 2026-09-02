package pro.deta.orion.agent.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class CborSequenceBuffer {
    private final AgentProtocolLimits limits;
    private byte[] pending = new byte[0];

    CborSequenceBuffer(AgentProtocolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    List<byte[]> accept(byte[] chunk) throws AgentProtocolException {
        Objects.requireNonNull(chunk, "chunk");
        byte[] combined = Arrays.copyOf(pending, pending.length + chunk.length);
        System.arraycopy(chunk, 0, combined, pending.length, chunk.length);

        List<byte[]> items = new ArrayList<>();
        int position = 0;
        while (position < combined.length) {
            int itemLength = CborItemScanner.itemLength(combined, position, limits);
            if (itemLength < 0) {
                break;
            }
            if (itemLength > limits.maxMessageBytes()) {
                throw new AgentProtocolException(
                        AgentProtocolException.Reason.LIMIT_EXCEEDED,
                        "CBOR item exceeds configured limit");
            }
            items.add(Arrays.copyOfRange(combined, position, position + itemLength));
            position += itemLength;
        }
        pending = Arrays.copyOfRange(combined, position, combined.length);
        if (pending.length > limits.maxMessageBytes()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "Incomplete CBOR item exceeds configured limit");
        }
        return List.copyOf(items);
    }

    int pendingBytes() {
        return pending.length;
    }
}
