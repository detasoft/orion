package pro.deta.orion.decision;

import pro.deta.orion.util.Result;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A self-contained choice and its continuation. Each implementation defines the displayed options and
 * executes the selected action using its captured context. The registry authorizes and accepts one answer
 * and chooses how to schedule execution outside its lock.
 * The result reports execution completion, not just acceptance of the answer. Cancellation can win only
 * while awaiting an answer; after acceptance the action owns completion, including any execution failure.
 */
public abstract class Decision {
    private final DecisionRequest request;
    private final AtomicBoolean pending = new AtomicBoolean(true);
    private final CompletableFuture<Result<DecisionAnswer>> result = new CompletableFuture<>();

    protected Decision(DecisionRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    public final DecisionRequest request() {
        return request;
    }

    public final CompletionStage<Result<DecisionAnswer>> result() {
        return result.minimalCompletionStage();
    }

    public final boolean cancel() {
        if (!pending.compareAndSet(true, false)) return false;
        result.completeExceptionally(new CancellationException("Decision request cancelled"));
        return true;
    }

    final boolean accept(DecisionAnswer answer) {
        Objects.requireNonNull(answer, "answer");
        return request.actions().containsKey(answer.action()) && pending.compareAndSet(true, false);
    }

    final void run(DecisionAnswer answer) {
        try {
            Result<Void> executed = Objects.requireNonNull(execute(answer), "decision execution result");
            result.complete(executed instanceof Result.Failure<Void> failure
                    ? new Result.Failure<>(failure) : Result.of(answer));
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    final void fail(RuntimeException failure) {
        result.complete(new Result.Failure<>(Result.FailureCode.GENERAL, "Decision execution failed", failure));
    }

    final boolean isPending() {
        return pending.get();
    }

    protected abstract Result<Void> execute(DecisionAnswer answer);
}
