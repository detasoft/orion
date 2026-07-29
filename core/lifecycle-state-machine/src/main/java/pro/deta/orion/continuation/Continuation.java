package pro.deta.orion.continuation;

import pro.deta.orion.util.Result;

import java.util.Objects;

/**
 * Represents the current step of an input-driven computation that can be paused,
 * resumed, and composed into larger state machines.
 *
 * <h3>Processing model</h3>
 * <p>A continuation receives one unit of input via {@link #process(Object)} and returns
 * a {@link ContinuationFlow} directive that tells the {@link ContinuationRuntime} what
 * to do next:
 * <ul>
 *   <li>{@link ContinuationFlow.Await} — consumed all it can; wait for more input.</li>
 *   <li>{@link ContinuationFlow.Continue} — still has input to consume; loop again with
 *       the same input chunk.</li>
 *   <li>{@link ContinuationFlow.Transition} — done with this step; hand off to another
 *       continuation, which is immediately re-driven with the same input.</li>
 *   <li>{@link ContinuationFlow.Yield} — needs an external task to complete before it
 *       can proceed (analogous to {@code SSLEngineResult.HandshakeStatus.NEED_TASK});
 *       the runtime suspends until {@link ContinuationRuntime#resumeTask()} is called
 *       by the owning handler.</li>
 * </ul>
 *
 * <h3>Signalling completion from {@code process()}</h3>
 * <p>When a continuation finishes, return a
 * {@link ContinuationFlow#transition(Continuation) transition} to one of the terminal
 * factories:
 * <ul>
 *   <li>{@link #completedSuccess(Continuation) completedSuccess(this)} — successful
 *       completion; pass {@code this} (the current continuation) as the {@code result}
 *       argument so that callers can inspect the final state. Do <em>not</em> construct
 *       a dummy placeholder.</li>
 *   <li>{@link #completedError(String, Throwable) completedError(message, e)} —
 *       failure; the description and exception are stored for inspection and are
 *       <em>not</em> re-thrown by the runtime.</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * <p>Implementations must <em>not</em> throw from {@link #process} — all errors must be
 * signalled via a transition to
 * {@link #completedError(String, Throwable) completedError}.
 *
 * <h3>Resource cleanup</h3>
 * <p>Override {@link #close()} to release resources. The runtime calls it automatically
 * when transitioning away from the continuation.
 */
@FunctionalInterface
public interface Continuation<I> {
    /**
     * Processes one input and returns the next flow directive.
     *
     * <p>Implementations must not throw — errors must be signalled by returning a
     * {@link ContinuationFlow#transition(Continuation) transition} to
     * {@link Continuation#completedError(String, Throwable) completedError}.
     */
    ContinuationFlow<I> process(I input);

    default void close() {
    }

    static <I> CompletedSuccess<I> completedSuccess(Continuation<I> result) {
        return new CompletedSuccess<>(result);
    }

    static <I> CompletedError<I> completedError(String message, Throwable throwable) {
        return new CompletedError<>(message, throwable);
    }

    static <I> CompletedError<I> completedError(String message) {
        return new CompletedError<>(message, new IllegalStateException(message));
    }

    static <I> CompletedError<I> completedError(Result.Failure<I> failure) {
        return new CompletedError<>(failure.message(), failure.throwable());
    }

    static <I> CompletedTimeout<I> completedTimeout(Continuation<I> continuation, long timeout, String message) {
        return new CompletedTimeout<>(continuation, timeout, message);
    }

    static <I> CompletedClosed<I> completedClosed(Continuation<I> continuation, String reason) {
        return new CompletedClosed<>(continuation, reason);
    }

    default boolean terminal() {
        return false;
    }

     interface TerminalContinuation<I> extends Continuation<I> {
        @Override
        default ContinuationFlow<I> process(I input) {
            throw new IllegalStateException("Terminal continuation does not process input");
        }

        @Override
        default boolean terminal() {
            return true;
        }
    }

    record CompletedSuccess<I>(Continuation<I> result) implements TerminalContinuation<I> { }

    record CompletedError<I>(String message, Throwable throwable) implements TerminalContinuation<I> {
        public CompletedError {
            if (Objects.requireNonNull(message, "message").isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            Objects.requireNonNull(throwable, "throwable");
        }

    }

    record CompletedTimeout<I>(Continuation<I> continuation, long timeout, String message) implements TerminalContinuation<I> {
        public CompletedTimeout {
            Objects.requireNonNull(continuation, "continuation");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * The owner closed the runtime while this continuation had not reached a terminal
     * state on its own — e.g. the underlying connection dropped.
     */
    record CompletedClosed<I>(Continuation<I> continuation, String reason) implements TerminalContinuation<I> {
        public CompletedClosed {
            Objects.requireNonNull(continuation, "continuation");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
