package pro.deta.orion.agentd.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

final class ControlConnectionLoop implements AutoCloseable {
    private static final Duration INITIAL_RECONNECT_BACKOFF = Duration.ofMillis(250);
    private static final Duration MAXIMUM_RECONNECT_BACKOFF = Duration.ofSeconds(30);
    private static final Duration STABLE_CONNECTION_PERIOD = Duration.ofMinutes(1);

    private final Runnable reconnect;
    private final Runnable heartbeat;
    private final LongSupplier nanoTime;
    private final RandomGenerator random;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> heartbeatTask;
    private Duration heartbeatInterval;
    private long connectedAtNanos;
    private int reconnectFailures;
    private boolean started;
    private boolean online;
    private boolean closed;

    ControlConnectionLoop(
            Runnable reconnect,
            Runnable heartbeat,
            LongSupplier nanoTime,
            RandomGenerator random
    ) {
        this(reconnect, heartbeat, nanoTime, random, newScheduler());
    }

    ControlConnectionLoop(
            Runnable reconnect,
            Runnable heartbeat,
            LongSupplier nanoTime,
            RandomGenerator random,
            ScheduledExecutorService scheduler
    ) {
        this.reconnect = Objects.requireNonNull(reconnect, "reconnect");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.random = Objects.requireNonNull(random, "random");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    synchronized void start() {
        if (closed || started) {
            return;
        }
        started = true;
        if (online) {
            scheduleHeartbeat();
        } else {
            scheduleReconnect();
        }
    }

    synchronized void connected(Duration interval) {
        if (closed) {
            return;
        }
        heartbeatInterval = Objects.requireNonNull(interval, "interval");
        online = true;
        connectedAtNanos = nanoTime.getAsLong();
        cancel(reconnectTask);
        reconnectTask = null;
    }

    synchronized void disconnected() {
        if (closed) {
            return;
        }
        if (online && nanoTime.getAsLong() - connectedAtNanos >= STABLE_CONNECTION_PERIOD.toNanos()) {
            reconnectFailures = 0;
        }
        online = false;
        cancel(heartbeatTask);
        heartbeatTask = null;
        if (started) {
            scheduleReconnect();
        }
    }

    synchronized void reconnectSucceeded() {
        scheduleHeartbeat();
    }

    synchronized void reconnectFailed() {
        if (closed) {
            return;
        }
        online = false;
        reconnectFailures = Math.min(reconnectFailures + 1, 30);
        scheduleReconnect();
    }

    synchronized void heartbeatSucceeded() {
        scheduleHeartbeat();
    }

    synchronized void heartbeatFailed() {
        if (closed || !online) {
            return;
        }
        online = false;
        cancel(heartbeatTask);
        heartbeatTask = null;
        scheduleReconnect();
    }

    synchronized boolean isOnline() {
        return online;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        online = false;
        cancel(reconnectTask);
        cancel(heartbeatTask);
        reconnectTask = null;
        heartbeatTask = null;
        scheduler.shutdownNow();
    }

    private void scheduleReconnect() {
        if (closed || online || reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        reconnectTask = scheduler.schedule(() -> {
            synchronized (ControlConnectionLoop.this) {
                reconnectTask = null;
                if (closed || online) {
                    return;
                }
            }
            reconnect.run();
        }, jitteredBackoffNanos(), TimeUnit.NANOSECONDS);
    }

    private void scheduleHeartbeat() {
        if (closed || !started || !online) {
            return;
        }
        cancel(heartbeatTask);
        heartbeatTask = scheduler.schedule(() -> {
            synchronized (ControlConnectionLoop.this) {
                heartbeatTask = null;
                if (closed || !online) {
                    return;
                }
            }
            heartbeat.run();
        }, heartbeatInterval.toNanos(), TimeUnit.NANOSECONDS);
    }

    private long jitteredBackoffNanos() {
        long exponential = INITIAL_RECONNECT_BACKOFF.toNanos();
        long maximum = MAXIMUM_RECONNECT_BACKOFF.toNanos();
        for (int index = 0; index < reconnectFailures && exponential < maximum; index++) {
            exponential = Math.min(maximum, exponential * 2);
        }
        return Math.max(1, Math.min(maximum, (long) (exponential * (0.5d + random.nextDouble()))));
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agentd-control-loop");
            thread.setDaemon(true);
            return thread;
        });
    }
}
