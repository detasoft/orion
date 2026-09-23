package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Bounded in-memory owner of pending requests. The supplied authorization check evaluates current rights
 * for each read or answer; callers supply authenticated principal addresses. Completion handlers execute
 * outside the registry lock. Closing rejects new requests and cancels outstanding waits.
 */
public final class DecisionRegistry implements AutoCloseable {
    private final int capacity;
    private final BiPredicate<PrincipalAddress, Optional<ConfigurationScope>> authorization;
    private final Map<UUID, PendingDecision> requests = new LinkedHashMap<>();
    private boolean closed;

    public DecisionRegistry(int capacity,
            BiPredicate<PrincipalAddress, Optional<ConfigurationScope>> authorization) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public Result<PendingDecision> register(Optional<ConfigurationScope> scope,
            String title, String description, Map<String, String> actions) {
        synchronized (requests) {
            if (closed) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED, "Decision registry is closed");
            }
            requests.values().removeIf(request -> !request.isPending());
            if (requests.size() >= capacity) {
                return new Result.Failure<>(Result.FailureCode.CREATION_FAILED, "Decision registry is full");
            }
            DecisionRequest request = new DecisionRequest(UUID.randomUUID(), Instant.now(),
                    scope, title, description, actions);
            PendingDecision pending = new PendingDecision(request);
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
        List<PendingDecision> snapshot;
        synchronized (requests) {
            snapshot = List.copyOf(requests.values());
        }
        List<DecisionRequest> visible = new ArrayList<>();
        for (PendingDecision pending : snapshot) {
            if (authorized(actor, pending.request().scope()) && pending.isPending()) {
                visible.add(pending.request());
            }
        }
        return List.copyOf(visible);
    }

    public Optional<DecisionRequest> find(UUID id, PrincipalAddress actor) {
        PendingDecision pending = accessible(id, actor);
        return pending == null ? Optional.empty() : Optional.of(pending.request());
    }

    public Result<Decision> decide(UUID id, Decision decision) {
        Objects.requireNonNull(decision, "decision");
        PendingDecision pending = accessible(id, decision.actor());
        if (pending == null) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        if (!pending.request().actions().containsKey(decision.action())) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Decision action is unavailable");
        }
        if (!pending.decide(decision)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Decision request is unavailable");
        }
        return Result.of(decision);
    }

    @Override
    public void close() {
        List<PendingDecision> outstanding;
        synchronized (requests) {
            closed = true;
            outstanding = List.copyOf(requests.values());
            requests.clear();
        }
        for (PendingDecision pending : outstanding) {
            pending.cancel();
        }
    }

    private PendingDecision accessible(UUID id, PrincipalAddress actor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        PendingDecision pending;
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
