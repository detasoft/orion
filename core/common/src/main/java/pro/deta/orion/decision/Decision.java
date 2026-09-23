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

/**
 * Owns a choice and its execution state. Failed actions stay open; only an explicitly repeatable selected action
 * may start another attempt. Each attempt has its own result, so a failure releases existing waiters immediately.
 * Handlers and result callbacks run outside the state lock. Running actions cannot be cancelled or run twice.
 * Resource keys must keep stable equality for the decision's lifetime; scope separates identical resource keys.
 */
public class Decision {
    private final Object resource;
    private final List<DecisionAction> actions;
    private final UUID id = UUID.randomUUID();
    private final Instant createdAt = Instant.now();
    private final Optional<ConfigurationScope> scope;
    private final String title;
    private final String description;
    private DecisionRequest.State state = DecisionRequest.State.PENDING;
    private int selectedAction = -1;
    private String error = "";
    private CompletableFuture<Result<DecisionAnswer>> result = new CompletableFuture<>();

    public Decision(Object resource, Optional<ConfigurationScope> scope, String title, String description,
            List<DecisionAction> actions) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.actions = List.copyOf(actions);
        this.scope = scope;
        this.title = title;
        this.description = description;
        request();
    }

    public final Object resource() { return resource; }

    public final List<DecisionAction> actions() { return actions; }

    public final synchronized DecisionRequest request() {
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        for (int index = 0; index < actions.size(); index++) {
            titles.put(Integer.toString(index), actions.get(index).title());
        }
        return new DecisionRequest(id, createdAt, scope, title, description, titles,
                state, error, selectedAction,
                state == DecisionRequest.State.FAILED && actions.get(selectedAction).retryable());
    }

    public final synchronized CompletionStage<Result<DecisionAnswer>> result() {
        return result.minimalCompletionStage();
    }

    public final boolean cancel() {
        CompletableFuture<Result<DecisionAnswer>> attempt;
        synchronized (this) {
            if (state != DecisionRequest.State.PENDING && state != DecisionRequest.State.FAILED) return false;
            state = DecisionRequest.State.CLOSED;
            attempt = result;
        }
        attempt.completeExceptionally(new CancellationException("Decision request cancelled"));
        return true;
    }

    final synchronized boolean accept(DecisionAnswer answer, boolean retry) {
        Objects.requireNonNull(answer, "answer");
        int action = answer.action();
        if (action < 0 || action >= actions.size()) return false;
        if (retry && state == DecisionRequest.State.FAILED) {
            if (action != selectedAction || !actions.get(action).retryable()) return false;
            result = new CompletableFuture<>();
        } else if (retry || state != DecisionRequest.State.PENDING) {
            return false;
        }
        selectedAction = action;
        state = DecisionRequest.State.RUNNING;
        error = "";
        return true;
    }

    final void run(DecisionAnswer answer) {
        try {
            Result<Void> executed = Objects.requireNonNull(actions.get(answer.action()).handler().apply(answer.actor()),
                    "decision execution result");
            complete(executed instanceof Result.Failure<Void> failure
                    ? new Result.Failure<>(failure) : Result.of(answer));
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    final void fail(RuntimeException failure) {
        complete(new Result.Failure<>(Result.FailureCode.GENERAL, "Decision execution failed", failure));
    }

    private void complete(Result<DecisionAnswer> outcome) {
        CompletableFuture<Result<DecisionAnswer>> attempt;
        synchronized (this) {
            state = outcome.isFailure() ? DecisionRequest.State.FAILED : DecisionRequest.State.CLOSED;
            error = outcome instanceof Result.Failure<DecisionAnswer> failed
                    ? Objects.requireNonNullElse(failed.message(), "Decision execution failed") : "";
            attempt = result;
        }
        attempt.complete(outcome);
    }

    final synchronized boolean isPending() { return state == DecisionRequest.State.PENDING; }

    final synchronized boolean isDone() { return state == DecisionRequest.State.CLOSED; }
}
