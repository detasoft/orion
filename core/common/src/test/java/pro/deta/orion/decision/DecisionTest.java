package pro.deta.orion.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import pro.deta.orion.schema.orion.ConfigurationScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionTest {
    private DecisionRegistry registry = new DecisionRegistry(2, Runnable::run, (actor, scope) -> true);

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    private boolean answer(Decision decision, DecisionAnswer answer) {
        return !registry.decide(decision.request().id(), answer).isFailure();
    }

    private static final DecisionAnswer ACCEPT = new DecisionAnswer(0, PrincipalAddress.parse("system/alice"));
    private static final DecisionAnswer REJECT = new DecisionAnswer(1, PrincipalAddress.parse("system/bob"));

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void concreteDecisionOwnsItsVariantsAndFinishesAfterItsAction(int selected) {
        java.util.ArrayDeque<Runnable> work = new java.util.ArrayDeque<>();
        java.util.List<String> actions = new java.util.ArrayList<>();
        registry.close();
        registry = new DecisionRegistry(2, work::add, (actor, scope) -> true);
        Decision pending = new Decision(UUID.randomUUID(), Optional.empty(), "Choose", "", List.of(
                new DecisionAction("Add", false, actor -> { actions.add("add:" + actor); return Result.of(null); }),
                new DecisionAction("Replace", false, actor -> { actions.add("replace:" + actor); return Result.of(null); }),
                new DecisionAction("Skip", false, actor -> { actions.add("skip:" + actor); return Result.of(null); })));
        registry.register(pending).valueOrFailure("register");
        DecisionAnswer replace = new DecisionAnswer(selected, ACCEPT.actor());
        assertThat(answer(pending, replace)).isTrue();
        assertThat(pending.isPending()).isFalse();
        assertThat(pending.cancel()).isFalse();
        assertThat(answer(pending, new DecisionAnswer(2, ACCEPT.actor()))).isFalse();
        assertThat(pending.result().toCompletableFuture()).isNotDone();
        assertThat(actions).isEmpty();
        assertThat(work).hasSize(1);
        work.remove().run();
        assertThat(actions).containsExactly(List.of("add", "replace", "skip").get(selected) + ":" + ACCEPT.actor());
        assertThat(pending.result().toCompletableFuture().join()).isEqualTo(Result.of(replace));
    }

    @Test
    void executorRejectionCompletesWithFailureWithoutExecutingTheAction() {
        java.util.concurrent.RejectedExecutionException failure =
                new java.util.concurrent.RejectedExecutionException("stopped");
        registry.close();
        registry = new DecisionRegistry(2, command -> { throw failure; }, (actor, scope) -> true);
        Decision pending = new Decision(UUID.randomUUID(), Optional.empty(), "Choose", "",
                List.of(new DecisionAction("Accept", false, actor -> {
                    throw new AssertionError("Rejected work must not execute");
                })));
        registry.register(pending).valueOrFailure("register");
        assertThat(answer(pending, ACCEPT)).isTrue();
        assertThat(pending.result().toCompletableFuture().join()).isInstanceOfSatisfying(Result.Failure.class,
                rejected -> assertThat(rejected.throwable()).isSameAs(failure));
        assertThat(answer(pending, ACCEPT)).isFalse();
    }

    @Test
    void executionFailureCompletesWithItsOriginalCause() {
        IllegalStateException failure = new IllegalStateException("could not save");
        Decision pending = new Decision(UUID.randomUUID(), Optional.empty(), "Choose", "",
                List.of(new DecisionAction("Accept", false, actor -> { throw failure; })));
        registry.register(pending).valueOrFailure("register");
        assertThat(answer(pending, ACCEPT)).isTrue();
        assertThat(pending.result().toCompletableFuture().join()).isInstanceOfSatisfying(Result.Failure.class,
                rejected -> assertThat(rejected.throwable()).isSameAs(failure));
    }

    @Test
    void anExceptionCanSupplyItsReadyDecisionWithoutAdditionalContext() {
        class ResolvableFailure extends RuntimeException implements Decisionable {
            private final Decision decision = new Decision(UUID.randomUUID(), Optional.empty(), "Choose", "",
                    List.of(new DecisionAction("Accept", false, actor -> Result.of(null))));
            @Override public Decision decision() { return decision; }
        }
        Decisionable failure = new ResolvableFailure();
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            Decision decision = registry.register(failure.decision()).valueOrFailure("register");
            registry.decide(decision.request().id(), ACCEPT).valueOrFailure("answer");
            assertThat(decision.result().toCompletableFuture().join()).isEqualTo(Result.of(ACCEPT));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"acme", "acme/platform", "acme/platform/api"})
    void retainsSystemOrOrganizationalScopeWhenDecisionCompletes(String scopePath) {
        Optional<ConfigurationScope> scope = Optional.ofNullable(scopePath).map(ConfigurationScope::parse);
        DecisionRequest request = new DecisionRequest(UUID.randomUUID(), Instant.now(), scope,
                "Confirm operation", "", Map.of("accept", "Accept"), DecisionRequest.State.PENDING, "", -1, false);
        Decision pending = decision(request);
        assertThat(pending.request().scope()).isEqualTo(scope);
        assertThat(answer(pending, ACCEPT)).isTrue();
        assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().join())
                .isEqualTo(ACCEPT);
        assertThat(pending.request().scope()).isEqualTo(scope);
    }

    @Test
    void deliversFirstDecisionToWaitingAndLateConsumers() throws Exception {
        Decision pending = pending();
        CompletableFuture<DecisionAnswer> waiting = pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture();
        assertThat(pending.isPending()).isTrue();
        assertThat(waiting).isNotDone();
        assertThat(answer(pending, ACCEPT)).isTrue();
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isEqualTo(ACCEPT);
        assertThat(pending.isPending()).isFalse();
        assertThat(answer(pending, REJECT)).isFalse();
        assertThat(pending.cancel()).isFalse();
        assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS))
                .isEqualTo(ACCEPT);
    }

    @Test
    void unknownActionDoesNotConsumeRequest() {
        Decision pending = pending();
        assertThat(answer(pending, new DecisionAnswer(2, ACCEPT.actor()))).isFalse();
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.result().toCompletableFuture()).isNotDone();
        assertThat(answer(pending, REJECT)).isTrue();
    }

    @Test
    void cancellationCompletesWaitAndPreventsDecision() {
        Decision pending = pending();
        CompletableFuture<DecisionAnswer> waiting = pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture();
        assertThat(pending.cancel()).isTrue();
        assertThat(pending.cancel()).isFalse();
        assertThat(answer(pending, ACCEPT)).isFalse();
        assertThat(pending.isPending()).isFalse();
        assertThatThrownBy(waiting::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CancellationException.class);
    }

    @Test
    void consumerCannotCompleteOrCancelOriginalWait() throws Exception {
        Decision pending = pending();
        assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().complete(ACCEPT)).isTrue();
        assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().cancel(false)).isTrue();
        assertThat(pending.isPending()).isTrue();
        assertThat(answer(pending, REJECT)).isTrue();
        assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS))
                .isEqualTo(REJECT);
    }

    @Test
    void availableActionsCannotChangeWhileWaiting() {
        List<DecisionAction> actions = new ArrayList<>();
        actions.add(new DecisionAction("Accept", false, actor -> Result.of(null)));
        actions.add(new DecisionAction("Reject", false, actor -> Result.of(null)));
        Decision pending = new Decision(UUID.randomUUID(), Optional.empty(), "Choose", "", actions);
        registry.register(pending).valueOrFailure("register");
        actions.clear();
        actions.add(new DecisionAction("Replace", false, actor -> Result.of(null)));
        assertThat(pending.actions()).extracting(DecisionAction::title).containsExactly("Accept", "Reject");
        assertThat(pending.request().actions().keySet()).containsExactly("0", "1");
        assertThatThrownBy(() -> pending.actions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pending.request().actions().put("2", "Replace"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(answer(pending, new DecisionAnswer(2, ACCEPT.actor()))).isFalse();
        assertThat(answer(pending, ACCEPT)).isTrue();
    }

    @Test
    void simultaneousDecisionsHaveOneWinner() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                Decision pending = pending();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> accept = executor.submit(() -> {
                    start.await();
                    return answer(pending, ACCEPT);
                });
                Future<Boolean> reject = executor.submit(() -> {
                    start.await();
                    return answer(pending, REJECT);
                });
                start.countDown();
                boolean accepted = accept.get(2, TimeUnit.SECONDS);
                boolean rejected = reject.get(2, TimeUnit.SECONDS);
                assertThat(accepted).isNotEqualTo(rejected);
                assertThat(pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS))
                        .isEqualTo(accepted ? ACCEPT : REJECT);
            }
        }
    }

    @Test
    void simultaneousDecisionAndCancellationHaveOneWinner() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                Decision pending = pending();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> decision = executor.submit(() -> {
                    start.await();
                    return answer(pending, ACCEPT);
                });
                Future<Boolean> cancel = executor.submit(() -> {
                    start.await();
                    return pending.cancel();
                });
                start.countDown();
                boolean decided = decision.get(2, TimeUnit.SECONDS);
                boolean cancelled = cancel.get(2, TimeUnit.SECONDS);
                assertThat(decided).isNotEqualTo(cancelled);
                assertThat(pending.isPending()).isFalse();
                CompletableFuture<DecisionAnswer> outcome = pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture();
                if (decided) {
                    assertThat(outcome.get(1, TimeUnit.SECONDS)).isEqualTo(ACCEPT);
                } else {
                    assertThatThrownBy(outcome::join)
                            .isInstanceOf(CompletionException.class)
                            .hasCauseInstanceOf(CancellationException.class);
                }
            }
        }
    }
    private Decision decision(DecisionRequest request) {
        List<DecisionAction> actions = new ArrayList<>();
        for (String title : new TreeMap<>(request.actions()).values()) {
            actions.add(new DecisionAction(title, false, actor -> Result.of(null)));
        }
        return registry.register(new Decision(UUID.randomUUID(), request.scope(), request.title(),
                request.description(), actions)).valueOrFailure("register");
    }
    private Decision pending() {
        return decision(request(Map.of("accept", "Accept", "reject", "Reject")));
    }
    private static DecisionRequest request(Map<String, String> actions) {
        return new DecisionRequest(UUID.randomUUID(), Instant.now(), Optional.empty(),
                "Confirm operation", "", actions, DecisionRequest.State.PENDING, "", -1, false);
    }
}
