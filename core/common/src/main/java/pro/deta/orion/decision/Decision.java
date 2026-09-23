package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.util.Result;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A self-contained choice with an immutable list of titled handlers and an equality-based resource identity.
 * Resource keys must keep stable equality for the decision's lifetime; scope separates identical local keys.
 * Each handler owns its captured context. The registry authorizes and accepts one answer
 * and chooses how to schedule execution outside its lock.
 * The result reports execution completion, not just acceptance of the answer. Cancellation can win only
 * while awaiting an answer; after acceptance the action owns completion, including any execution failure.
 */
public class Decision {
    private final Object resource;
    private final List<DecisionAction> actions;
    private final DecisionRequest request;
    private final AtomicBoolean pending = new AtomicBoolean(true);
    private final CompletableFuture<Result<DecisionAnswer>> result = new CompletableFuture<>();

    public Decision(Object resource, Optional<ConfigurationScope> scope, String title, String description,
            List<DecisionAction> actions) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.actions = List.copyOf(actions);
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        for (int index = 0; index < this.actions.size(); index++) {
            titles.put(Integer.toString(index), this.actions.get(index).title());
        }
        this.request = new DecisionRequest(UUID.randomUUID(), Instant.now(), scope, title, description, titles);
    }

    public final Object resource() {
        return resource;
    }

    public final List<DecisionAction> actions() {
        return actions;
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
        return answer.action() >= 0 && answer.action() < actions.size() && pending.compareAndSet(true, false);
    }

    final void run(DecisionAnswer answer) {
        try {
            Result<Void> executed = Objects.requireNonNull(actions.get(answer.action()).handler().apply(answer.actor()),
                    "decision execution result");
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

    final boolean isDone() {
        return result.isDone();
    }

}
