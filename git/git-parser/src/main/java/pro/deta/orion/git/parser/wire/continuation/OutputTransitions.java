package pro.deta.orion.git.parser.wire.continuation;

import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;

import java.io.IOException;
import java.util.Objects;

public final class OutputTransitions {
    private OutputTransitions() {
    }

    public static <I> ContinuationFlow<I> transitionAfterOutput(
            OutputOperation operation,
            Continuation<I> next,
            String failureMessage) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(failureMessage, "failureMessage");
        try {
            operation.run();
            return ContinuationFlow.transition(next);
        } catch (IOException | RuntimeException error) {
            return ContinuationFlow.completedError(failureMessage, error);
        }
    }

    @FunctionalInterface
    public interface OutputOperation {
        void run() throws IOException;
    }
}
