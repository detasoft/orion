package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;

/**
 * Bounded in-memory owner of pending requests. The supplied authorization check evaluates current rights
 * for each read or answer; callers supply authenticated principal addresses. Completion handlers execute
 * outside the registry lock. The registry schedules accepted actions on the supplied executor;
 * the runtime owns that executor's lifecycle. Closing rejects new requests and cancels outstanding waits.
 * Registration returns the existing decision for the same scope and resource until success or dismissal.
 * Callers must use the returned decision as the authoritative result. Capacity limits all unresolved requests,
 * including running and failed actions.
 */
public final class DecisionRegistry implements AutoCloseable {
    private final int capacity;
    private final Executor executor;
    private final BiPredicate<PrincipalAddress, Optional<ConfigurationScope>> authorization;
    private final Map<UUID, Decision> requests = new LinkedHashMap<>();
    private boolean closed;

    public DecisionRegistry(int capacity, Executor executor,
            BiPredicate<PrincipalAddress, Optional<ConfigurationScope>> authorization) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public Result<Decision> register(Decision pending) {
        Objects.requireNonNull(pending, "decision");
        synchronized (requests) {
            if (closed) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED, "Decision registry is closed");
            }
            requests.values().removeIf(Decision::isDone);
            DecisionRequest request = pending.request();
            if (requests.get(request.id()) == pending) {
                return Result.of(pending);
            }
            if (!pending.isPending()) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED,
                        "Decision is no longer pending");
            }
            for (Decision existing : requests.values()) {
                if (existing.resource().equals(pending.resource())
                        && existing.request().scope().equals(request.scope())) {
                    return Result.of(existing);
                }
            }
            if (requests.size() >= capacity) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED, "Decision registry is full");
            }
            requests.put(request.id(), pending);
            pending.result().whenComplete((decision, failure) -> {
                removeCompleted(pending);
            });
            return Result.of(pending);
        }
    }

    public List<DecisionRequest> list(PrincipalAddress actor) {
        Objects.requireNonNull(actor, "actor");
        List<Decision> snapshot;
        synchronized (requests) {
            snapshot = List.copyOf(requests.values());
        }
        List<DecisionRequest> visible = new ArrayList<>();
        for (Decision pending : snapshot) {
            DecisionRequest request = pending.request();
            if (request.state() != DecisionRequest.State.CLOSED && authorized(actor, request.scope())) {
                visible.add(request);
            }
        }
        return List.copyOf(visible);
    }

    public Optional<DecisionRequest> find(UUID id, PrincipalAddress actor) {
        Decision pending = accessible(id, actor);
        return pending == null ? Optional.empty() : Optional.of(pending.request());
    }

    public Result<DecisionAnswer> decide(UUID id, DecisionAnswer decision) {
        return submit(id, decision, false);
    }

    public Result<DecisionAnswer> retry(UUID id, PrincipalAddress actor) {
        Decision decision = accessible(id, actor);
        if (decision == null) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        return submit(id, new DecisionAnswer(decision.request().selectedAction(), actor), true);
    }

    private Result<DecisionAnswer> submit(UUID id, DecisionAnswer decision, boolean retry) {
        Objects.requireNonNull(decision, "decision");
        Decision pending = accessible(id, decision.actor());
        if (pending == null) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        if (decision.action() < 0 || decision.action() >= pending.actions().size()) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Decision action is unavailable");
        }
        if (!pending.accept(decision, retry)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        pending.result().whenComplete((answer, failure) -> removeCompleted(pending));
        try {
            executor.execute(() -> pending.run(decision));
        } catch (RuntimeException failure) {
            pending.fail(failure);
        }
        return Result.of(decision);
    }

    public Result<Void> dismiss(UUID id, PrincipalAddress actor) {
        Decision decision = accessible(id, actor);
        if (decision == null || decision.request().state() != DecisionRequest.State.FAILED || !decision.cancel()) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Failed decision is unavailable");
        }
        removeCompleted(decision);
        return Result.of(null);
    }

    private void removeCompleted(Decision decision) {
        synchronized (requests) {
            if (decision.isDone()) requests.remove(decision.request().id(), decision);
        }
    }

    @Override
    public void close() {
        List<Decision> outstanding;
        synchronized (requests) {
            closed = true;
            outstanding = List.copyOf(requests.values());
            requests.clear();
        }
        for (Decision pending : outstanding) {
            pending.cancel();
        }
    }

    private Decision accessible(UUID id, PrincipalAddress actor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Decision pending;
        synchronized (requests) {
            pending = requests.get(id);
        }
        if (pending == null || !authorized(actor, pending.request().scope()) || pending.isDone()) {
            return null;
        }
        return pending;
    }

    private boolean authorized(PrincipalAddress actor, Optional<ConfigurationScope> scope) {
        if (actor instanceof PrincipalAddress.OrganizationPrincipalAddress organization) {
            if (scope.isEmpty() || !scope.orElseThrow().organizationId().equals(organization.organizationId())) {
                return false;
            }
        }
        return authorization.test(actor, scope);
    }
}
