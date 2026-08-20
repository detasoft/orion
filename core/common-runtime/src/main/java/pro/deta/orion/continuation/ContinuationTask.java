package pro.deta.orion.continuation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Runnable yield work whose actual completion may be asynchronous.
 */
public interface ContinuationTask extends Runnable {
    CompletionStage<Void> completion();

    static CompletionStage<Void> completionOf(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (task instanceof ContinuationTask continuationTask) {
            return Objects.requireNonNull(
                    continuationTask.completion(),
                    "task completion");
        }
        return CompletableFuture.completedFuture(null);
    }
}
