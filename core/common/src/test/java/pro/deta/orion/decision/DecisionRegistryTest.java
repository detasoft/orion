package pro.deta.orion.decision;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRegistryTest {
    @Test
    void closingRegistryDoesNotShutDownTheSuppliedExecutor() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DecisionRegistry registry = new DecisionRegistry(1, executor, (actor, scope) -> true);
            Decision pending = register(registry, null);
            registry.close();
            assertThat(pending.result().toCompletableFuture()).isCompletedExceptionally();
            assertThat(executor.isShutdown()).isFalse();
            assertThat(executor.submit(() -> "still running").get(2, TimeUnit.SECONDS)).isEqualTo("still running");
        }
    }

    @Test
    void refusesDuplicateRegistrationAndCompletedDecisions() {
        try (DecisionRegistry registry = registry(2)) {
            Decision pending = register(registry, null);
            assertThat(registry.register(pending).isFailure()).isTrue();
            assertThat(registry.list(ADMIN)).containsExactly(pending.request());
            registry.decide(pending.request().id(), new DecisionAnswer("accept", ADMIN))
                    .valueOrFailure("answer");
            assertThat(registry.register(pending).isFailure()).isTrue();
            assertThat(registry.list(ADMIN)).isEmpty();
        }
    }

    @Test
    void acceptedActionLeavesTheQueueAndSurvivesRegistryShutdown() {
        java.util.ArrayDeque<Runnable> work = new java.util.ArrayDeque<>();
        java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
        try (DecisionRegistry registry = new DecisionRegistry(1, work::add, (actor, scope) -> true)) {
            Decision pending = new Decision(new DecisionRequest(UUID.randomUUID(), Instant.now(),
                    Optional.empty(), "Resume", "", Map.of("resume", "Resume"))) {
                @Override protected Result<Void> execute(DecisionAnswer answer) {
                    executed.incrementAndGet();
                    return Result.of(null);
                }
            };
            registry.register(pending).valueOrFailure("register");
            registry.decide(pending.request().id(), new DecisionAnswer("resume", ADMIN)).valueOrFailure("answer");
            assertThat(registry.list(ADMIN)).isEmpty();
            Decision next = register(registry, null);
            registry.close();
            assertThat(next.result().toCompletableFuture()).isCompletedExceptionally();
            assertThat(pending.result().toCompletableFuture()).isNotDone();
            work.remove().run();
            assertThat(executed).hasValue(1);
            assertThat(pending.result().toCompletableFuture().join().isFailure()).isFalse();
        }
    }

    private static final PrincipalAddress ADMIN = PrincipalAddress.parse("system/admin");
    private static final PrincipalAddress ALICE = PrincipalAddress.parse("acme/alice");
    private static final PrincipalAddress BOB = PrincipalAddress.parse("acme/bob");

    @Test
    void organizationBoundaryCannotBeBypassedByPermissiveAuthorization() {
        try (DecisionRegistry registry = new DecisionRegistry(4, Runnable::run, (actor, scope) -> true)) {
            PrincipalAddress actor = PrincipalAddress.parse("acme/root");
            Decision own = register(registry, "acme/team/repo");
            Decision other = register(registry, "other/team/repo");
            Decision system = register(registry, null);
            assertThat(registry.list(actor)).containsExactly(own.request());
            for (Decision hidden : List.of(other, system)) {
                assertThat(registry.find(hidden.request().id(), actor)).isEmpty();
                assertThat(registry.decide(hidden.request().id(), new DecisionAnswer("accept", actor)).isFailure()).isTrue();
                assertThat(hidden.result().toCompletableFuture()).isNotDone();
            }
            assertThat(registry.decide(own.request().id(), new DecisionAnswer("accept", actor)).isFailure()).isFalse();
        }
    }

    @Test
    void registersListsAndResolvesRequest() throws Exception {
        try (DecisionRegistry registry = registry(2)) {
            Decision first = register(registry, null);
            Decision second = register(registry, "acme/platform/api");

            assertThat(first.request().id()).isNotEqualTo(second.request().id());
            assertThat(registry.list(ADMIN)).containsExactly(first.request(), second.request());
            assertThat(registry.find(first.request().id(), ADMIN)).contains(first.request());
            DecisionAnswer decision = new DecisionAnswer("accept", ADMIN);
            assertThat(registry.decide(first.request().id(), decision)).isEqualTo(Result.of(decision));
            assertThat(first.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS))
                .isEqualTo(decision);
            assertThat(registry.list(ADMIN)).containsExactly(second.request());
            assertThat(registry.find(first.request().id(), ADMIN)).isEmpty();
            assertThat(registry.decide(first.request().id(), decision)).isInstanceOf(Result.Failure.class);
        }
    }

    @Test
    void appliesScopeAuthorizationToListLookupAndDecision() {
        try (DecisionRegistry registry = registry(5)) {
            Decision system = register(registry, null);
            Decision other = register(registry, "other");
            Decision organization = register(registry, "acme");
            Decision team = register(registry, "acme/platform");
            Decision repository = register(registry, "acme/platform/api");

            assertThat(registry.list(ALICE))
                    .containsExactly(organization.request(), team.request(), repository.request());
            assertThat(registry.find(system.request().id(), ALICE)).isEmpty();
            assertThat(registry.find(other.request().id(), ALICE)).isEmpty();
            assertThat(registry.decide(other.request().id(), new DecisionAnswer("accept", ALICE)))
                    .isEqualTo(registry.decide(UUID.randomUUID(), new DecisionAnswer("accept", ALICE)));
            assertThat(other.isPending()).isTrue();
            assertThat(registry.decide(repository.request().id(), new DecisionAnswer("accept", BOB)))
                    .isInstanceOf(Result.Success.class);
        }
    }

    @Test
    void reevaluatesAuthorizationAfterAccessIsRevoked() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> allowed.get())) {
            Decision pending = register(registry, "acme");
            assertThat(registry.list(ALICE)).containsExactly(pending.request());
            allowed.set(false);

            assertThat(registry.list(ALICE)).isEmpty();
            assertThat(registry.find(pending.request().id(), ALICE)).isEmpty();
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer("accept", ALICE)))
                    .isInstanceOf(Result.Failure.class);
            assertThat(pending.isPending()).isTrue();
        }
    }

    @Test
    void unknownActionLeavesRequestAvailable() {
        try (DecisionRegistry registry = registry(1)) {
            Decision pending = register(registry, null);
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer("replace", ADMIN)))
                    .isInstanceOf(Result.Failure.class);
            assertThat(registry.list(ADMIN)).containsExactly(pending.request());
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer("reject", ADMIN)))
                    .isInstanceOf(Result.Success.class);
        }
    }

    @Test
    void capacityIsReleasedByOwnerCancellation() {
        try (DecisionRegistry registry = registry(1)) {
            Decision pending = register(registry, null);
            assertThat(submit(registry, null)).isInstanceOf(Result.Failure.class);
            assertThat(registry.list(ADMIN)).containsExactly(pending.request());

            assertThat(pending.cancel()).isTrue();
            assertThat(registry.list(ADMIN)).isEmpty();
            assertThat(submit(registry, null)).isInstanceOf(Result.Success.class);
        }
    }

    @Test
    void closeCancelsOutstandingWaitsAndPreventsRegistration() {
        DecisionRegistry registry = registry(2);
        Decision first = register(registry, null);
        Decision second = register(registry, "acme");
        registry.close();
        registry.close();
        for (Decision pending : List.of(first, second)) {
            assertThatThrownBy(() -> pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CancellationException.class);
        }
        assertThat(registry.list(ADMIN)).isEmpty();
        assertThat(submit(registry, null)).isInstanceOf(Result.Failure.class);
        assertThat(registry.decide(first.request().id(), new DecisionAnswer("accept", ADMIN)))
                .isInstanceOf(Result.Failure.class);
    }

    @Test
    void completionCanRegisterNextRequestFromAnotherThread() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                DecisionRegistry registry = registry(1)) {
            Decision pending = register(registry, null);
            CompletableFuture<Decision> next = pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution")).thenApply(decision -> {
                try {
                    return executor.submit(() -> register(registry, "acme")).get(2, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }).toCompletableFuture();
            assertThat(registry.decide(pending.request().id(), new DecisionAnswer("accept", ADMIN)))
                    .isInstanceOf(Result.Success.class);
            assertThat(registry.list(ADMIN)).containsExactly(next.get(2, TimeUnit.SECONDS).request());
        }
    }

    @Test
    void concurrentRegistrationsRespectCapacity() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2); DecisionRegistry registry = registry(1)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Result<Decision>> first = executor.submit(() -> {
                start.await();
                return submit(registry, null);
            });
            Future<Result<Decision>> second = executor.submit(() -> {
                start.await();
                return submit(registry, null);
            });
            start.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).isFailure())
                    .isNotEqualTo(second.get(2, TimeUnit.SECONDS).isFailure());
            assertThat(registry.list(ADMIN)).hasSize(1);
        }
    }

    @Test
    void concurrentAuthorizedAnswersDeliverOneDecision() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2); DecisionRegistry registry = registry(1)) {
            Decision pending = register(registry, "acme");
            DecisionAnswer accept = new DecisionAnswer("accept", ALICE);
            DecisionAnswer reject = new DecisionAnswer("reject", BOB);
            CountDownLatch start = new CountDownLatch(1);
            Future<Result<DecisionAnswer>> first = executor.submit(() -> {
                start.await();
                return registry.decide(pending.request().id(), accept);
            });
            Future<Result<DecisionAnswer>> second = executor.submit(() -> {
                start.await();
                return registry.decide(pending.request().id(), reject);
            });
            start.countDown();
            Result<DecisionAnswer> accepted = first.get(2, TimeUnit.SECONDS);
            Result<DecisionAnswer> rejected = second.get(2, TimeUnit.SECONDS);
            assertThat(accepted.isFailure()).isNotEqualTo(rejected.isFailure());
            assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS))
                    .isEqualTo(accepted.isFailure() ? reject : accept);
            assertThat(registry.list(ADMIN)).isEmpty();
        }
    }

    private static DecisionRegistry registry(int capacity) {
        ConfigurationScope organization = ConfigurationScope.parse("acme");
        return new DecisionRegistry(capacity, Runnable::run, (actor, scope) -> actor.equals(ADMIN)
                || ((actor.equals(ALICE) || actor.equals(BOB))
                && scope.isPresent() && organization.isSameOrAncestorOf(scope.orElseThrow())));
    }
    private static Decision register(DecisionRegistry registry, String scope) {
        return submit(registry, scope).valueOrFailure("register decision request");
    }
    private static Result<Decision> submit(DecisionRegistry registry, String scope) {
        return registry.register(new Decision(new DecisionRequest(UUID.randomUUID(), Instant.now(),
                Optional.ofNullable(scope).map(ConfigurationScope::parse),
                "Confirm operation", "", Map.of("accept", "Accept", "reject", "Reject"))) {
                    @Override protected Result<Void> execute(DecisionAnswer answer) { return Result.of(null); }
                });
    }
}
