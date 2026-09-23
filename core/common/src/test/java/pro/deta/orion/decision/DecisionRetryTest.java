package pro.deta.orion.decision;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRetryTest {
    private static final PrincipalAddress ACTOR = PrincipalAddress.parse("system/admin");

    @Test
    void keepsFailureAndRetriesOnlySelectedActionWithASeparateAttemptResult() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AtomicInteger attempts = new AtomicInteger();
        try (DecisionRegistry registry = new DecisionRegistry(1, work::add, (actor, scope) -> true)) {
            Decision decision = new Decision("resource", Optional.empty(), "Save", "", List.of(
                    new DecisionAction("Save", true, actor -> attempts.incrementAndGet() == 1
                            ? new Result.Failure<>(Result.FailureCode.GENERAL, "Storage unavailable") : Result.of(null)),
                    new DecisionAction("Other", true, actor -> Result.of(null))));
            registry.register(decision).valueOrFailure("register");
            var first = decision.result().toCompletableFuture();
            registry.decide(decision.request().id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("answer");
            assertThat(registry.list(ACTOR).getFirst().state()).isEqualTo(DecisionRequest.State.RUNNING);
            assertThat(registry.decide(decision.request().id(), new DecisionAnswer(0, ACTOR)).isFailure()).isTrue();
            work.remove().run();
            assertThat(first.join().isFailure()).isTrue();
            assertThat(registry.list(ACTOR).getFirst().error()).isEqualTo("Storage unavailable");
            assertThat(decision.request().retryable()).isTrue();
            assertThat(registry.register(new Decision("resource", Optional.empty(), "Duplicate", "",
                    List.of(new DecisionAction("Accept", false, actor -> Result.of(null)))))
                    .valueOrFailure("duplicate")).isSameAs(decision);
            assertThat(registry.decide(decision.request().id(), new DecisionAnswer(1, ACTOR)).isFailure()).isTrue();
            assertThat(registry.decide(decision.request().id(), new DecisionAnswer(0, ACTOR)).isFailure()).isTrue();
            registry.retry(decision.request().id(), ACTOR).valueOrFailure("retry");
            var second = decision.result().toCompletableFuture();
            assertThat(second).isNotDone();
            assertThat(first.join().isFailure()).isTrue();
            work.remove().run();
            assertThat(second.join().isFailure()).isFalse();
            assertThat(attempts).hasValue(2);
            assertThat(registry.list(ACTOR)).isEmpty();
        }
    }

    @Test
    void failedNonrepeatableActionRemainsVisibleAndCountsTowardCapacityUntilDismissed() {
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            Decision failed = failing("first");
            registry.register(failed).valueOrFailure("register");
            registry.decide(failed.request().id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("answer");
            assertThat(failed.request().state()).isEqualTo(DecisionRequest.State.FAILED);
            assertThat(failed.request().retryable()).isFalse();
            assertThat(registry.retry(failed.request().id(), ACTOR).isFailure()).isTrue();
            assertThat(registry.register(failing("second")).isFailure()).isTrue();
            assertThat(registry.dismiss(failed.request().id(), PrincipalAddress.parse("acme/member")).isFailure())
                    .isTrue();
            registry.dismiss(failed.request().id(), ACTOR).valueOrFailure("dismiss");
            assertThat(registry.list(ACTOR)).isEmpty();
            assertThat(registry.register(failing("second")).isFailure()).isFalse();
        }
    }

    @Test
    void concurrentRetriesScheduleOneAttemptAndExecutorRejectionStaysVisible() throws Exception {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        java.util.concurrent.atomic.AtomicBoolean reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (DecisionRegistry registry = new DecisionRegistry(1, command -> {
            if (reject.get()) throw new java.util.concurrent.RejectedExecutionException("Stopped");
            synchronized (work) { work.add(command); }
        }, (actor, scope) -> true);
             java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            Decision decision = new Decision("resource", Optional.empty(), "Retry", "",
                    List.of(new DecisionAction("Retry", true, actor -> Result.of(null))));
            registry.register(decision).valueOrFailure("register");
            registry.decide(decision.request().id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("answer");
            assertThat(decision.request().state()).isEqualTo(DecisionRequest.State.FAILED);
            assertThat(decision.result().toCompletableFuture().join().isFailure()).isTrue();
            reject.set(false);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> retry = () -> {
                start.await();
                return !registry.retry(decision.request().id(), ACTOR).isFailure();
            };
            java.util.concurrent.Future<Boolean> first = callers.submit(retry);
            java.util.concurrent.Future<Boolean> second = callers.submit(retry);
            start.countDown();
            assertThat(List.of(first.get(2, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(2, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(work).hasSize(1);
            work.remove().run();
            assertThat(registry.list(ACTOR)).isEmpty();
        }
    }

    @Test
    void failureWithoutMessageStillHasAVisibleDescription() {
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            Decision decision = new Decision("resource", Optional.empty(), "Fail", "",
                    List.of(new DecisionAction("Fail", false, actor -> new Result.Failure<>(Result.FailureCode.GENERAL))));
            registry.register(decision).valueOrFailure("register");
            registry.decide(decision.request().id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("answer");
            assertThat(registry.list(ACTOR).getFirst().error()).isEqualTo("Decision execution failed");
        }
    }

    private static Decision failing(String resource) {
        return new Decision(resource, Optional.empty(), "Fail", "",
                List.of(new DecisionAction("Fail", false, actor -> {
                    throw new IllegalStateException("unexpected failure");
                })));
    }
}
