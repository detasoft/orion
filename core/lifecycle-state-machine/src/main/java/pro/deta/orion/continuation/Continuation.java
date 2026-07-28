package pro.deta.orion.continuation;

import java.util.Objects;

/**
 * Represents the current continuation of an input-driven computation.
 *
 * <h3>Signalling completion from {@code process()}</h3>
 * <p>When a continuation finishes, return a
 * {@link ContinuationFlow#transition(Continuation) transition} to one of the terminal factories:
 * <ul>
 *   <li>{@link #completedSuccess(Continuation) completedSuccess(this)} — successful completion;
 *       pass {@code this} (the current continuation) as the {@code result} argument so that
 *       callers can inspect the final state. Do <em>not</em> construct a dummy placeholder.</li>
 *   <li>{@link #completedError(Throwable) completedError(e)} — failure; the exception is stored
 *       for inspection and is <em>not</em> re-thrown by the runtime.</li>
 * </ul>
 */
@FunctionalInterface
public interface Continuation<I> {
    static <I> Continuation<I> completedSuccess(Continuation<I> result) {
        return new CompletedSuccess<>(result);
    }

    static <I> Continuation<I> completedError(Throwable error) {
        return new CompletedError<>(error);
    }

    static <I> Continuation<I> completedTimeout(Continuation<I> current, long timeout, String message) {
        return new CompletedTimeout<>(current, timeout, message);
    }

    /**
     * Processes one input and returns the next flow directive.
     *
     * <p>Implementations must not throw — errors must be signalled by returning a
     * {@link ContinuationFlow#transition(Continuation) transition} to
     * {@link Continuation#completedError(Throwable) completedError}.
     */
    ContinuationFlow<I> process(I input);

    default boolean terminal() {
        return false;
    }

    record CompletedSuccess<I>(Continuation<I> result) implements Continuation<I> {
        @Override
        public ContinuationFlow<I> process(I input) {
            throw new IllegalStateException("Terminal continuation does not process input");
        }

        @Override
        public boolean terminal() {
            return true;
        }
    }

    record CompletedError<I>(Throwable error) implements Continuation<I> {
        public CompletedError {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public ContinuationFlow<I> process(I input) {
            throw new IllegalStateException("Terminal continuation does not process input");
        }

        @Override
        public boolean terminal() {
            return true;
        }
    }

    record CompletedTimeout<I>(Continuation<I> current, long timeout, String message) implements Continuation<I> {
        public CompletedTimeout {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public ContinuationFlow<I> process(I input) {
            throw new IllegalStateException("Terminal continuation does not process input");
        }

        @Override
        public boolean terminal() {
            return true;
        }
    }
}
