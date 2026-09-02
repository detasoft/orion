package pro.deta.orion.agent.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class ProtocolBytes {
    private final byte[] value;

    private ProtocolBytes(byte[] value) {
        this.value = value;
    }

    public static ProtocolBytes copyOf(byte[] value) {
        Objects.requireNonNull(value, "value");
        return new ProtocolBytes(Arrays.copyOf(value, value.length));
    }

    static ProtocolBytes copyOf(byte[] value, int from, int to) {
        return new ProtocolBytes(Arrays.copyOfRange(value, from, to));
    }

    public int size() {
        return value.length;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(value, value.length);
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof ProtocolBytes bytes && Arrays.equals(value, bytes.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "ProtocolBytes[size=" + value.length + "]";
    }
}
