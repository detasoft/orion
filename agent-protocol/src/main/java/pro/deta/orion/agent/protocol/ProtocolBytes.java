package pro.deta.orion.agent.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class ProtocolBytes {
    private final byte[] value;
    private final int from;
    private final int to;

    private ProtocolBytes(byte[] value, int from, int to) {
        this.value = Objects.requireNonNull(value, "value");
        Objects.checkFromToIndex(from, to, value.length);
        this.from = from;
        this.to = to;
    }

    public static ProtocolBytes copyOf(byte[] value) {
        Objects.requireNonNull(value, "value");
        byte[] copy = Arrays.copyOf(value, value.length);
        return new ProtocolBytes(copy, 0, copy.length);
    }

    static ProtocolBytes copyOf(byte[] value, int from, int to) {
        byte[] copy = Arrays.copyOfRange(value, from, to);
        return new ProtocolBytes(copy, 0, copy.length);
    }

    ProtocolBytes slice(int from, int to) {
        Objects.checkFromToIndex(from, to, size());
        return new ProtocolBytes(value, this.from + from, this.from + to);
    }

    CborReader cborReader(AgentProtocolLimits limits) {
        return new CborReader(value, from, to, limits);
    }

    public int size() {
        return to - from;
    }

    public byte[] toByteArray() {
        return Arrays.copyOfRange(value, from, to);
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof ProtocolBytes bytes
                && Arrays.equals(value, from, to, bytes.value, bytes.from, bytes.to);
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int index = from; index < to; index++) {
            result = 31 * result + value[index];
        }
        return result;
    }

    @Override
    public String toString() {
        return "ProtocolBytes[size=" + size() + "]";
    }
}
