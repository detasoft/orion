package pro.deta.orion.agentd.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ControlConnectionLoopTest {
    @Test
    void ownsReconnectBackoffHeartbeatSchedulingAndStableReset() throws Exception {
        AtomicLong nanoTime = new AtomicLong();
        AtomicInteger reconnects = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        RecordingScheduler scheduler = new RecordingScheduler(true);
        ControlConnectionLoop loop = new ControlConnectionLoop(
                reconnects::incrementAndGet, heartbeats::incrementAndGet,
                nanoTime::get, new Random(1), scheduler);

        loop.start();
        await(() -> reconnects.get() == 1);
        assertThat(scheduler.delaysNanos.get(0))
                .isBetween(Duration.ofMillis(125).toNanos(), Duration.ofMillis(375).toNanos());

        loop.reconnectFailed();
        await(() -> reconnects.get() == 2);
        assertThat(scheduler.delaysNanos.get(1))
                .isBetween(Duration.ofMillis(250).toNanos(), Duration.ofMillis(750).toNanos());

        loop.connected(Duration.ofMillis(10));
        loop.reconnectSucceeded();
        await(() -> heartbeats.get() == 1);
        loop.heartbeatSucceeded();
        await(() -> heartbeats.get() == 2);

        nanoTime.set(Duration.ofMinutes(2).toNanos());
        loop.disconnected();
        await(() -> reconnects.get() == 3);
        assertThat(scheduler.delaysNanos.get(4))
                .isBetween(Duration.ofMillis(125).toNanos(), Duration.ofMillis(375).toNanos());

        loop.close();
    }

    @Test
    void keepsOnlyOneTimerForEachOperationAndCancelsBothOnClose() throws Exception {
        AtomicInteger reconnects = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        RecordingScheduler scheduler = new RecordingScheduler(false);
        ControlConnectionLoop loop = new ControlConnectionLoop(
                reconnects::incrementAndGet, heartbeats::incrementAndGet,
                System::nanoTime, new Random(1), scheduler);

        loop.disconnected();
        loop.disconnected();
        loop.start();
        loop.disconnected();
        await(() -> reconnects.get() == 1);

        loop.connected(Duration.ofDays(1));
        loop.connected(Duration.ofDays(1));
        loop.close();
        TimeUnit.MILLISECONDS.sleep(25);

        assertThat(reconnects).hasValue(1);
        assertThat(heartbeats).hasValue(0);
        assertThat(scheduler.getQueue()).isEmpty();
        assertThat(scheduler.isShutdown()).isTrue();
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(condition.evaluate()).isTrue();
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate();
    }

    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        private final List<Long> delaysNanos = new CopyOnWriteArrayList<>();
        private final boolean shortenDelays;

        private RecordingScheduler(boolean shortenDelays) {
            super(1);
            this.shortenDelays = shortenDelays;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            long nanos = unit.toNanos(delay);
            delaysNanos.add(nanos);
            long actual = shortenDelays && nanos < TimeUnit.MINUTES.toNanos(1)
                    ? TimeUnit.MILLISECONDS.toNanos(1) : nanos;
            return super.schedule(command, actual, TimeUnit.NANOSECONDS);
        }
    }
}
