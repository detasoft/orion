package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.SessionId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SessionCommandLanesTest {
    private static final SessionId FIRST = new SessionId("first");
    private static final SessionId SECOND = new SessionId("second");

    @Test
    void serializesOneSessionWithoutBlockingAnotherAndReportsCapacity() throws Exception {
        try (SessionCommandLanes lanes = new SessionCommandLanes(2, 2, 2)) {
            lanes.connected();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Integer> order = new ArrayList<>();
            var first = lanes.submit(FIRST, () -> {
                started.countDown();
                release.await();
                synchronized (order) {
                    order.add(1);
                }
                return 1;
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var second = lanes.submit(FIRST, () -> {
                synchronized (order) {
                    order.add(2);
                }
                return 2;
            });

            assertThat(result(lanes.submit(FIRST, () -> 3)))
                    .isEqualTo(new SessionCommandLanes.Outcome.Discarded<>(
                            SessionCommandLanes.Reason.CAPACITY));
            assertThat(result(lanes.submit(SECOND, () -> 4)))
                    .isEqualTo(new SessionCommandLanes.Outcome.Completed<>(4));
            release.countDown();
            assertThat(result(first)).isEqualTo(new SessionCommandLanes.Outcome.Completed<>(1));
            assertThat(result(second)).isEqualTo(new SessionCommandLanes.Outcome.Completed<>(2));
            assertThat(order).containsExactly(1, 2);
        }
    }

    @Test
    void discardsPendingWorkOnDisconnectAndGatesNewWorkUntilConnected() throws Exception {
        try (SessionCommandLanes lanes = new SessionCommandLanes(2, 2, 2)) {
            lanes.connected();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var running = lanes.submit(FIRST, () -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var pending = lanes.submit(FIRST, () -> 2);

            lanes.disconnected();
            assertThat(result(pending)).isEqualTo(new SessionCommandLanes.Outcome.Discarded<>(
                    SessionCommandLanes.Reason.DISCONNECTED));
            assertThat(result(lanes.submit(FIRST, () -> 3)))
                    .isEqualTo(new SessionCommandLanes.Outcome.Discarded<>(
                            SessionCommandLanes.Reason.DISCONNECTED));
            lanes.connected();
            var next = lanes.submit(FIRST, () -> 4);
            assertThat(next.toCompletableFuture()).isNotDone();

            release.countDown();
            assertThat(result(running)).isEqualTo(new SessionCommandLanes.Outcome.Completed<>(1));
            assertThat(result(next)).isEqualTo(new SessionCommandLanes.Outcome.Completed<>(4));
        }
    }

    @Test
    void failedActionDoesNotStopItsLaneAndCloseRejectsAdmission() throws Exception {
        try (SessionCommandLanes lanes = new SessionCommandLanes(2, 2, 2)) {
            lanes.connected();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var failed = lanes.submit(FIRST, () -> {
                started.countDown();
                release.await();
                throw new IllegalStateException("failure");
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var following = lanes.submit(FIRST, () -> 2);
            release.countDown();

            assertThatExceptionOfType(java.util.concurrent.ExecutionException.class)
                    .isThrownBy(() -> failed.toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .withCauseInstanceOf(IllegalStateException.class);
            assertThat(result(following)).isEqualTo(new SessionCommandLanes.Outcome.Completed<>(2));
            lanes.close();
            assertThat(result(lanes.submit(FIRST, () -> 3)))
                    .isEqualTo(new SessionCommandLanes.Outcome.Discarded<>(
                            SessionCommandLanes.Reason.CLOSED));
        }
    }

    private static <T> SessionCommandLanes.Outcome<T> result(
            java.util.concurrent.CompletionStage<SessionCommandLanes.Outcome<T>> stage
    ) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
}
