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
            requests.values().removeIf(request -> !request.isPending());
            if (requests.size() >= capacity) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED, "Decision registry is full");
            }
            DecisionRequest request = pending.request();
            if (!pending.isPending() || requests.containsKey(request.id())) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED,
                        "Decision is already registered or no longer pending");
            }
            requests.put(request.id(), pending);
            pending.result().whenComplete((decision, failure) -> {
                synchronized (requests) {
                    requests.remove(request.id(), pending);
                }
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
            if (authorized(actor, pending.request().scope()) && pending.isPending()) {
                visible.add(pending.request());
            }
        }
        return List.copyOf(visible);
    }

    public Optional<DecisionRequest> find(UUID id, PrincipalAddress actor) {
        Decision pending = accessible(id, actor);
        return pending == null ? Optional.empty() : Optional.of(pending.request());
    }

    public Result<DecisionAnswer> decide(UUID id, DecisionAnswer decision) {
        Objects.requireNonNull(decision, "decision");
        Decision pending = accessible(id, decision.actor());
        if (pending == null) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        if (!pending.request().actions().containsKey(decision.action())) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Decision action is unavailable");
        }
        if (!pending.accept(decision)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        synchronized (requests) {
            requests.remove(id, pending);
        }
        try {
            executor.execute(() -> pending.run(decision));
        } catch (RuntimeException failure) {
            pending.fail(failure);
        }
        return Result.of(decision);
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
        if (pending == null || !authorized(actor, pending.request().scope()) || !pending.isPending()) {
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
