package pro.deta.orion.agent.protocol;

import java.math.BigInteger;

public record EventId(long value) implements Comparable<EventId> {
    private static final BigInteger MAX_VALUE = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);

    public static EventId fromUnsigned(BigInteger value) {
        if (value.signum() < 0 || value.compareTo(MAX_VALUE) > 0) {
            throw new IllegalArgumentException("eventId must fit an unsigned 64-bit integer");
        }
        return new EventId(value.longValue());
    }

    public BigInteger toUnsignedBigInteger() {
        BigInteger signed = BigInteger.valueOf(value);
        return value >= 0 ? signed : signed.add(BigInteger.ONE.shiftLeft(Long.SIZE));
    }

    @Override
    public int compareTo(EventId other) {
        return Long.compareUnsigned(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value);
    }
}
