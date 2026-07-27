package pro.deta.orion.continuation;

import java.util.Objects;

/**
 * Represents the current continuation of an input-driven computation.
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
