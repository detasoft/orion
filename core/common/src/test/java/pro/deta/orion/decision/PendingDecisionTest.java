package pro.deta.orion.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;

import java.time.Instant;
import java.util.LinkedHashMap;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingDecisionTest {
    private static final Decision ACCEPT = new Decision("accept", PrincipalAddress.parse("system/alice"));
    private static final Decision REJECT = new Decision("reject", PrincipalAddress.parse("acme/bob"));

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"acme", "acme/platform", "acme/platform/api"})
    void retainsSystemOrOrganizationalScopeWhenDecisionCompletes(String scopePath) {
        Optional<ConfigurationScope> scope = Optional.ofNullable(scopePath).map(ConfigurationScope::parse);
        DecisionRequest request = new DecisionRequest(UUID.randomUUID(), Instant.now(), scope,
                "Confirm operation", "", Map.of("accept", "Accept"));
        PendingDecision pending = new PendingDecision(request);

        assertThat(pending.request().scope()).isEqualTo(scope);
        assertThat(pending.decide(ACCEPT)).isTrue();
        assertThat(pending.result().toCompletableFuture().join()).isEqualTo(ACCEPT);
        assertThat(pending.request().scope()).isEqualTo(scope);
    }

    @Test
    void deliversFirstDecisionToWaitingAndLateConsumers() throws Exception {
        PendingDecision pending = pending();
        CompletableFuture<Decision> waiting = pending.result().toCompletableFuture();

        assertThat(pending.isPending()).isTrue();
        assertThat(waiting).isNotDone();
        assertThat(pending.decide(ACCEPT)).isTrue();
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isEqualTo(ACCEPT);
        assertThat(pending.isPending()).isFalse();
        assertThat(pending.decide(REJECT)).isFalse();
        assertThat(pending.cancel()).isFalse();
        assertThat(pending.result().toCompletableFuture().get(1, TimeUnit.SECONDS)).isEqualTo(ACCEPT);
    }

    @Test
    void unknownActionDoesNotConsumeRequest() {
        PendingDecision pending = pending();

        assertThat(pending.decide(new Decision("replace", ACCEPT.actor()))).isFalse();
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.result().toCompletableFuture()).isNotDone();
        assertThat(pending.decide(REJECT)).isTrue();
    }

    @Test
    void cancellationCompletesWaitAndPreventsDecision() {
        PendingDecision pending = pending();
        CompletableFuture<Decision> waiting = pending.result().toCompletableFuture();

        assertThat(pending.cancel()).isTrue();
        assertThat(pending.cancel()).isFalse();
        assertThat(pending.decide(ACCEPT)).isFalse();
        assertThat(pending.isPending()).isFalse();
        assertThatThrownBy(waiting::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CancellationException.class);
    }

    @Test
    void consumerCannotCompleteOrCancelOriginalWait() throws Exception {
        PendingDecision pending = pending();

        assertThat(pending.result().toCompletableFuture().complete(ACCEPT)).isTrue();
        assertThat(pending.result().toCompletableFuture().cancel(false)).isTrue();
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.decide(REJECT)).isTrue();
        assertThat(pending.result().toCompletableFuture().get(1, TimeUnit.SECONDS)).isEqualTo(REJECT);
    }

    @Test
    void availableActionsCannotChangeWhileWaiting() {
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("accept", "Accept");
        actions.put("reject", "Reject");
        DecisionRequest request = request(actions);
        PendingDecision pending = new PendingDecision(request);

        actions.clear();
        actions.put("replace", "Replace");

        assertThat(pending.request()).isSameAs(request);
        assertThat(pending.request().actions().keySet()).containsExactly("accept", "reject");
        assertThatThrownBy(() -> pending.request().actions().put("replace", "Replace"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(pending.decide(new Decision("replace", ACCEPT.actor()))).isFalse();
        assertThat(pending.decide(ACCEPT)).isTrue();
    }

    @Test
    void simultaneousDecisionsHaveOneWinner() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                PendingDecision pending = pending();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> accept = executor.submit(() -> {
                    start.await();
                    return pending.decide(ACCEPT);
                });
                Future<Boolean> reject = executor.submit(() -> {
                    start.await();
                    return pending.decide(REJECT);
                });
                start.countDown();

                boolean accepted = accept.get(2, TimeUnit.SECONDS);
                boolean rejected = reject.get(2, TimeUnit.SECONDS);
                assertThat(accepted).isNotEqualTo(rejected);
                assertThat(pending.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        .isEqualTo(accepted ? ACCEPT : REJECT);
            }
        }
    }

    @Test
    void simultaneousDecisionAndCancellationHaveOneWinner() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                PendingDecision pending = pending();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> decision = executor.submit(() -> {
                    start.await();
                    return pending.decide(ACCEPT);
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
                CompletableFuture<Decision> outcome = pending.result().toCompletableFuture();
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

    private static PendingDecision pending() {
        return new PendingDecision(request(Map.of("accept", "Accept", "reject", "Reject")));
    }

    private static DecisionRequest request(Map<String, String> actions) {
        return new DecisionRequest(UUID.randomUUID(), Instant.now(), Optional.empty(),
                "Confirm operation", "", actions);
    }
}
