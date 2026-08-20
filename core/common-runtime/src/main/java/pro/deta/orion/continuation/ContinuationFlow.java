package pro.deta.orion.continuation;

import pro.deta.orion.util.Result;

import java.util.Objects;

/**
 * Describes what {@link Continuation#process(Object)} should do next.
 *
 * <h3>Flow directives</h3>
 * <ul>
 *   <li>{@link Await} — no more of the current input can be consumed; stop the drive
 *       loop and wait for the next {@link ContinuationRuntime#accept(Object)} call.
 *       Analogous to {@code SSLEngineResult.Status.BUFFER_UNDERFLOW}.</li>
 *
 *   <li>{@link Continue} — the current input still has data to offer; re-call
 *       {@link Continuation#process(Object) process} on the same continuation without
 *       returning to the caller. Use when a single chunk can advance the machine through
 *       multiple sub-states in one go.</li>
 *
 *   <li>{@link Transition} — switch to a different {@link Continuation} and immediately
 *       re-drive it with the same input, still inside the current drive-loop
 *       iteration.</li>
 *
 *   <li>{@link Yield} — the machine cannot proceed until an external, possibly
 *       asynchronous task completes. The drive loop suspends; the task {@link Runnable}
 *       must be scheduled by the caller, and {@link ContinuationRuntime#resumeTask()}
 *       called when done. Analogous to
 *       {@code SSLEngineResult.HandshakeStatus.NEED_TASK}.</li>
 *
 *   <li>{@link TransitionAndYield} — switch to a different continuation, then suspend
 *       as for {@link Yield}. Resumption re-drives the new continuation with the same
 *       input.</li>
 * </ul>
 *
 * <p>These are the only five directives a {@link Continuation#process(Object)}
 * implementation may return — the type is sealed to exactly these. What {@link
 * ContinuationRuntime#accept(Object)} and {@link ContinuationRuntime#resumeTask()}
 * return to their own caller is the wider {@link RuntimeFlow}, which additionally
 * reports {@link RuntimeFlow.Terminal} and {@link RuntimeFlow.Error} — signals a
 * continuation must never produce itself. {@link Await} and {@link Yield} are the only
 * directives common to both: they pass straight from a continuation through the runtime
 * unchanged, which is why they implement both sealed interfaces.
 */
public sealed interface ContinuationFlow<I>
        permits ContinuationFlow.Await,
                ContinuationFlow.Continue,
                ContinuationFlow.Transition,
                ContinuationFlow.TransitionAndYield,
                ContinuationFlow.Yield {

    static <I> Continue<I> continueFlow() {
        return new Continue<>();
    }

    static <I> Await<I> await() {
        return new Await<>();
    }

    static <I> Transition<I> transition(Continuation<I> next) {
        return new Transition<>(next);
    }

    static <I> Yield<I> yield(Runnable task) {
        return new Yield<>(task);
    }

    static <I> TransitionAndYield<I> transitionAndYield(
            Continuation<I> next,
            Runnable task) {
        return new TransitionAndYield<>(next, task);
    }

    /**
     * Convenience for the common {@code transition(Continuation.completedError(...))}
     * pattern used to fail the current continuation from within {@link
     * Continuation#process(Object)}.
     */
    static <I> Transition<I> completedError(String message, Throwable throwable) {
        return new Transition<>(Continuation.completedError(message, throwable));
    }

    static <I> Transition<I> completedError(String message) {
        return new Transition<>(Continuation.completedError(message, new IllegalStateException(message)));
    }

    static <I> Transition<I> completedError(Result.Failure<?> message) {
        return new Transition<>(Continuation.completedError(message.getMessage(), message.throwable()));
    }

    /**
     * Re-drive the same continuation with the same input without returning to the caller.
     */
    record Continue<I>() implements ContinuationFlow<I> {
    }

    /**
     * No more of the current input can be consumed; wait for the next input chunk.
     */
    record Await<I>() implements ContinuationFlow<I>, RuntimeFlow {
    }

    /**
     * Switch to the given continuation and re-drive it with the same input.
     */
    record Transition<I>(Continuation<I> next) implements ContinuationFlow<I> {
        public Transition {
            Objects.requireNonNull(next, "next");
        }
    }

    /**
     * Switch to the given continuation, then suspend until an external task completes.
     */
    record TransitionAndYield<I>(
            Continuation<I> next,
            Runnable task) implements ContinuationFlow<I> {
        public TransitionAndYield {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(task, "task");
        }
    }

    /**
     * Suspend the machine until an external task completes.
     *
     * <p>The runtime remembers the current input, then returns this {@code Yield}
     * itself from {@link ContinuationRuntime#accept(Object)}. No further input is
     * accepted until the yield is resolved. The runtime owns neither the input nor
     * {@link #task()}.
     *
     * <p>The {@link #task()} {@link Runnable} represents the work to perform — for
     * example, flushing an output buffer. Scheduler dispatch is the <em>caller's</em>
     * responsibility, not the task's. A typical Netty handler pattern:
     * <pre>{@code
     * // After accept() returns, check for a pending yield:
     * RuntimeFlow<ByteBuf> flow = runtime.accept(buf);
     * if (flow instanceof Yield<ByteBuf> yield) {
     *     outputScheduler.schedule(() -> {
     *         yield.task().run();                  // do the actual work
     *         if (eventLoop.inEventLoop()) {
     *             runtime.resumeTask();            // already on correct thread
     *         } else {
     *             eventLoop.execute(runtime::resumeTask); // dispatch back
     *         }
     *     });
     * }
     * }</pre>
     *
     * <p>Back-pressure cascades naturally: if the machine yields again after resumption,
     * the cycle repeats without any special-casing — the caller reads {@code task()} off
     * the {@code Yield} returned by {@link ContinuationRuntime#resumeTask()} this time.
     */
    record Yield<I>(Runnable task) implements ContinuationFlow<I>, RuntimeFlow {
        public Yield {
            Objects.requireNonNull(task, "task");
        }
    }
}
