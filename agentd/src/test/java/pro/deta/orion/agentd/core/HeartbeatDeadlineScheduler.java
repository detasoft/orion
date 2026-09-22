package pro.deta.orion.agentd.core;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

final class HeartbeatDeadlineScheduler extends ScheduledThreadPoolExecutor {
    private final LinkedBlockingQueue<Deadline> deadlines = new LinkedBlockingQueue<>();

    HeartbeatDeadlineScheduler() {
        super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ScheduledFuture<?> scheduled = super.schedule(command, delay, unit);
        if (unit.toNanos(delay) == TimeUnit.SECONDS.toNanos(30)) {
            deadlines.add(new Deadline(command, scheduled));
        }
        return scheduled;
    }

    void expireHeartbeat() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            Deadline deadline = deadlines.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS);
            assertThat(deadline).as("pending 30-second heartbeat deadline").isNotNull();
            if (!deadline.scheduled().isCancelled()) {
                deadline.scheduled().cancel(false);
                deadline.command().run();
                return;
            }
        }
        throw new AssertionError("no pending heartbeat deadline");
    }

    private record Deadline(Runnable command, ScheduledFuture<?> scheduled) { }
}
