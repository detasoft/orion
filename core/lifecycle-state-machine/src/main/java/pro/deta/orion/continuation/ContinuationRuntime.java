package pro.deta.orion.continuation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static pro.deta.orion.continuation.Continuation.completedTimeout;
import static pro.deta.orion.continuation.ContinuationFlow.transition;

/**
 * Drives one current continuation until it awaits input or reaches a terminal
 * continuation.
 */
public abstract class ContinuationRuntime<I> {
    private final LongSupplier ticker;
    private volatile long lastInputAtNanos;
    private volatile long lastRunAtNanos;

    private final AtomicReference<Continuation<I>> current;

    protected ContinuationRuntime(Continuation<I> initial) {
        this(initial, System::nanoTime);
    }

    protected ContinuationRuntime(Continuation<I> initial, LongSupplier ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        lastInputAtNanos = ticker.getAsLong();
    }

    public void accept(I input) {
        Objects.requireNonNull(input, "input");
        lastInputAtNanos = ticker.getAsLong();
        lastRunAtNanos = lastInputAtNanos;
        try {
            Continuation<I> active;
            while (!(active = current.get()).terminal()) {
                ContinuationFlow<I> flow = Objects.requireNonNull(active.process(input), "flow");
                switch (flow) {
                    case ContinuationFlow.Await<I> ignored -> { return; }
                    case ContinuationFlow.Continue<I> ignored -> {}
                    case ContinuationFlow.Transition<I> t -> transitionTo(active, t);
                }
            }
        } finally {
            lastRunAtNanos = 0;
        }
    }

    public final void tick() {
        if (terminal()) {
            return;
        }
        Continuation<I> snapshot = current.get();
        if (snapshot instanceof Timed timed) {
            long now = ticker.getAsLong();
            long elapsedNanos = now - lastInputAtNanos;
            long elapsedRunNanos = now - lastRunAtNanos;
            if (expired(elapsedNanos, timed.idleTimeoutNanos())) {
                transitionTo(snapshot, transition(completedTimeout(snapshot, elapsedNanos, "idle")));
            } else if (lastRunAtNanos > 0 && expired(elapsedRunNanos, timed.runtimeTimeoutNanos())) {
                transitionTo(snapshot, transition(completedTimeout(snapshot, elapsedRunNanos, "runtime")));
            }
        }
    }

    protected final long lastInputAtNanos() {
        return lastInputAtNanos;
    }

    private static boolean expired(long elapsedNanos, long timeoutNanos) {
        return timeoutNanos >= 0 && elapsedNanos >= timeoutNanos;
    }

    protected final Continuation<I> current() {
        return current.get();
    }

    public final boolean terminal() {
        return current.get().terminal();
    }

    protected final void transitionTo(
            Continuation<I> previous,
            ContinuationFlow.Transition<I> next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        if (next.next() == previous) {
            return;
        }
        if (current.compareAndSet(previous, next.next())) {
            if (previous instanceof Closed closed) {
                closed.close();
            }
            onTransition(previous, next.next());
        }
    }

    protected void onTransition(
            Continuation<I> previous,
            Continuation<I> next) {
    }
}
