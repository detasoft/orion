package pro.deta.orion.decision;

import pro.deta.orion.util.Result;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * Registers a ready decision carried by a failure, its causes or suppressed errors.
 * Resolution actions belong to the decision; this handler has no connection or configuration policy.
 * The propagated exception retains the original failure and the registry's canonical decision.
 */
public final class ConnectionFailureHandler {
    private final DecisionRegistry decisions;

    public ConnectionFailureHandler(DecisionRegistry decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    public Exception handle(Exception failure) {
        Objects.requireNonNull(failure, "failure");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> remaining = new ArrayDeque<>();
        remaining.add(failure);
        while (!remaining.isEmpty()) {
            Throwable current = remaining.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof Decisionable required) {
                Result<Decision> registered = decisions.register(required.decision());
                if (registered instanceof Result.Failure<Decision>) {
                    throw new RejectedExecutionException("Could not register connection decision", failure);
                }
                return new DecisionRequiredException(((Result.Success<Decision>) registered).value(), failure);
            }
            if (current.getCause() != null) remaining.addFirst(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) remaining.addLast(suppressed);
        }
        return failure;
    }
}
