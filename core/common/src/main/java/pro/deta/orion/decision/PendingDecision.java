package pro.deta.orion.decision;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * An in-memory request and its one-shot decision or cancellation. Only the registry may submit a decision,
 * after checking current permissions; the operation holding this handle may observe or cancel the wait.
 * Consumers dispatch their continuation to its owning executor using asynchronous completion handlers.
 */
public final class PendingDecision {
    private final DecisionRequest request;
    private final CompletableFuture<Decision> result = new CompletableFuture<>();

    PendingDecision(DecisionRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    public DecisionRequest request() {
        return request;
    }

    public CompletionStage<Decision> result() {
        return result.minimalCompletionStage();
    }

    public boolean cancel() {
        return result.completeExceptionally(new CancellationException("Decision request cancelled"));
    }

    boolean decide(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        return request.actions().containsKey(decision.action()) && result.complete(decision);
    }

    boolean isPending() {
        return !result.isDone();
    }
}
