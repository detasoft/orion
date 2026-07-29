package pro.deta.orion.continuation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static pro.deta.orion.continuation.Continuation.completedClosed;
import static pro.deta.orion.continuation.Continuation.completedError;
import static pro.deta.orion.continuation.Continuation.completedTimeout;
import static pro.deta.orion.continuation.ContinuationFlow.transition;
import static pro.deta.orion.continuation.RuntimeFlow.error;

/**
 * Drives one current continuation until it awaits input, yields, or reaches a terminal
 * continuation.
 *
 * <h3>Normal flow</h3>
 * <p>Call {@link #accept(Object)} with each new input chunk. The runtime drives the
 * active continuation in a tight loop, following {@link ContinuationFlow.Continue} and
 * {@link ContinuationFlow.Transition} directives, until the machine returns
 * {@link ContinuationFlow.Await} or reaches a terminal state.
 *
 * <h3>Yield — delegated task (NEED_TASK pattern)</h3>
 * <p>When the active continuation returns {@link ContinuationFlow.Yield}, the runtime
 * suspends: it remembers the pending input and returns that same
 * {@link ContinuationFlow.Yield} value from {@link #accept}. No further input is
 * accepted until the yield is resolved.
 *
 * <p>The {@link Runnable} inside {@code Yield} is the work to perform (e.g. flush an
 * output buffer). Scheduling it and dispatching the resumption back to the correct
 * thread is the <em>caller's</em> responsibility — the runtime is scheduler-agnostic;
 * it does not hold on to the task itself, only to the frozen input needed to re-drive.
 * Once the work is done the caller calls {@link #resumeTask()}, which re-enters the
 * drive loop with the frozen input. If the machine yields again, the cycle repeats —
 * the caller reads the {@code task()} off the newly returned {@code Yield}.
 *
 * <p>Example handler wiring (Netty):
 * <pre>{@code
 * // channelRead handler:
 * RuntimeFlow<ByteBuf> flow = runtime.accept(buf);
 * if (flow instanceof ContinuationFlow.Yield<ByteBuf> yield) {
 *     outputScheduler.schedule(() -> {
 *         yield.task().run();
 *         if (eventLoop.inEventLoop()) {
 *             runtime.resumeTask();
 *         } else {
 *             eventLoop.execute(runtime::resumeTask);
 *         }
 *     });
 * }
 * }</pre>
 *
 * <h3>Input ownership</h3>
 * <p>The runtime never retains or releases an input. An adapter for a
 * reference-counted transport must keep the input alive from the returned
 * {@link ContinuationFlow.Yield} until resumption no longer yields.
 *
 * <h3>Closing</h3>
 * <p>{@link #close(String)} is for the owner to force the runtime down — e.g. the
 * underlying connection dropped — while the active continuation has not reached a
 * terminal state on its own. It is idempotent: only the first call has any effect. It
 * transitions {@code current} to {@link Continuation.CompletedClosed}, carrying the
 * reason, exactly like any other terminal transition — no separate closed-flag is kept.
 * After it, {@link #terminal()} is {@code true} and {@link #accept(Object)}/
 * {@link #resumeTask()} immediately return {@link RuntimeFlow.Terminal}, the same as
 * any other terminal continuation, rather than re-entering the (already closed)
 * continuation.
 */
public class ContinuationRuntime<I> {
    private final LongSupplier ticker;
    private volatile long lastInputAtNanos;
    private volatile long lastRunAtNanos;

    private final AtomicReference<Continuation<I>> current;

    // Yield state — accessed only on the owning thread.
    private I pendingInput;

    public ContinuationRuntime(Continuation<I> initial) {
        this(initial, System::nanoTime);
    }

    protected ContinuationRuntime(Continuation<I> initial, LongSupplier ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        lastInputAtNanos = ticker.getAsLong();
    }

    /**
     * Feed the next input chunk to the active continuation.
     *
     * <p>Must not be called while a {@link ContinuationFlow.Yield} is pending —
     * i.e. between the moment the machine returns {@code Yield} and the matching
     * {@link #resumeTask()} call.
     *
     * @return the flow the drive loop stopped on: {@link RuntimeFlow.Terminal} if the
     *         continuation is (or just became) terminal, {@link ContinuationFlow.Await}
     *         if there is nothing more to consume right now, {@link ContinuationFlow.Yield}
     *         if an external task is pending, or a non-terminal {@link RuntimeFlow.Error}
     *         if the caller violated the API contract
     */
    public RuntimeFlow accept(I input) {
        if (input == null) {
            return error("Continuation input must not be null");
        }
        if (pendingInput != null) {
            return error("Cannot accept new input while a Yield task is pending");
        }
        return drive(input);
    }

    /**
     * Returns {@code true} if the machine is suspended, waiting for a yield task to
     * complete.
     */
    public final boolean isYielding() {
        return pendingInput != null;
    }

    /**
     * Resumes the machine after the pending yield task has been executed by the caller.
     *
     * <p>Re-enters the drive loop with the same input that was frozen at the yield point.
     * Must be called on the machine's owning thread/scheduler.
     *
     * @return the flow the drive loop stopped on this time — same cases as
     *         {@link #accept(Object)}; may be another {@link ContinuationFlow.Yield}
     *         if the machine yielded again, or a non-terminal {@link RuntimeFlow.Error}
     *         if resumption is invalid
     */
    public RuntimeFlow resumeTask() {
        if (pendingInput == null) {
            return error("No pending Yield task to resume");
        }
        I input = pendingInput;
        pendingInput = null;
        return drive(input);
    }

    /**
     * Closes the runtime. If the active continuation has not reached a terminal state
     * on its own, transitions it to {@link Continuation.CompletedClosed} carrying
     * {@code reason} — {@link #transitionTo} closes it in the process, same as any
     * other transition. Idempotent — later calls are no-ops.
     */
    public final void close(String reason) {
        Objects.requireNonNull(reason, "reason");
        pendingInput = null;
        Continuation<I> snapshot = current.get();
        if (snapshot.terminal()) {
            return;
        }
        transitionTo(snapshot, transition(completedClosed(snapshot, reason)));
    }

    private RuntimeFlow drive(I input) {
        lastInputAtNanos = ticker.getAsLong();
        lastRunAtNanos = lastInputAtNanos;
        try {
            Continuation<I> active;
            while ((active = current.get()) != null) {
                if (active.terminal())
                    return RuntimeFlow.terminal();

                ContinuationFlow<I> flow = Objects.requireNonNull(active.process(input), "flow");
                switch (flow) {
                    case ContinuationFlow.Await<I> a -> { return a; }
                    case ContinuationFlow.Continue<I> ignored -> {}
                    case ContinuationFlow.Transition<I> t -> transitionTo(active, t);
                    case ContinuationFlow.Yield<I> y -> {
                        pendingInput = input;
                        return y;
                    }
                }
            }
        } finally {
            lastRunAtNanos = 0;
        }
        // current is never null — an internal invariant broke, not a caller mistake.
        // Leave the runtime in a well-defined terminal state before failing loudly, so a
        // caller that catches this and keeps using the runtime sees Terminal, not an NPE.
        IllegalStateException failure = new IllegalStateException("current must never be null");
        current.set(completedError("current must never be null", failure));
        throw failure;
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
            previous.close();
            onTransition(previous, next.next());
        }
    }

    protected void onTransition(
            Continuation<I> previous,
            Continuation<I> next) {
    }
}
