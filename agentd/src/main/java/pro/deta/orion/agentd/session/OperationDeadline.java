package pro.deta.orion.agentd.session;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class OperationDeadline {
    private final long startedNanos;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private final OperationDeadline outer;

    public static OperationDeadline after(Duration timeout) {
        return after(timeout, System::nanoTime);
    }

    public static OperationDeadline after(Duration timeout, LongSupplier nanoTime) {
        Objects.requireNonNull(nanoTime, "nanoTime");
        return new OperationDeadline(nanoTime.getAsLong(), timeoutNanos(timeout), nanoTime, null);
    }

    public OperationDeadline boundedBy(Duration timeout) {
        long boundedNanos = timeoutNanos(timeout);
        return new OperationDeadline(nanoTime.getAsLong(), boundedNanos, nanoTime, this);
    }

    public long remainingNanos() {
        long elapsed = nanoTime.getAsLong() - startedNanos;
        if (elapsed < 0 || elapsed >= timeoutNanos) {
            return 0;
        }
        long remaining = timeoutNanos - elapsed;
        return outer == null ? remaining : Math.min(remaining, outer.remainingNanos());
    }

    public boolean expired() {
        return remainingNanos() == 0;
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("timeout cannot be represented in nanoseconds", error);
        }
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeoutNanos;
    }

    private OperationDeadline(
            long startedNanos,
            long timeoutNanos,
            LongSupplier nanoTime,
            OperationDeadline outer
    ) {
        this.startedNanos = startedNanos;
        this.timeoutNanos = timeoutNanos;
        this.nanoTime = nanoTime;
        this.outer = outer;
    }
}
