package pro.deta.orion.agent.server.journal;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounds pending maintenance by distinct session and coalesces all work for one session into one task.
 * Enqueue never waits for an executor task. Failures are diagnostic only and never poison journal appends.
 */
final class JournalMaintenance implements AutoCloseable {
    private static final int DEFAULT_MAX_PENDING_SESSIONS = 64;
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private final Executor executor;
    private final ExecutorService dispatcher;
    private final int maxPendingSessions;
    private final Map<SessionJournal, Work> workBySession = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean accepting = true;
    private int activeTasks;

    static JournalMaintenance background() {
        return new JournalMaintenance(Runnable::run, DEFAULT_MAX_PENDING_SESSIONS);
    }

    private static ExecutorService dispatcher() {
        ThreadFactory threads = task -> {
            Thread thread = new Thread(
                    task,
                    "session-journal-maintenance-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threads);
    }

    @TestOnly
    static JournalMaintenance disabled() {
        return new JournalMaintenance(command -> { }, 0);
    }

    @TestOnly
    static JournalMaintenance using(Executor executor, int maxPendingSessions) {
        return new JournalMaintenance(executor, maxPendingSessions);
    }

    private JournalMaintenance(Executor executor, int maxPendingSessions) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maxPendingSessions < 0) {
            throw new IllegalArgumentException("maxPendingSessions must not be negative");
        }
        this.maxPendingSessions = maxPendingSessions;
        dispatcher = maxPendingSessions == 0 ? null : dispatcher();
    }

    void enqueue(SessionJournal journal) {
        Work work;
        synchronized (this) {
            if (!accepting || maxPendingSessions == 0) {
                return;
            }
            Work existing = workBySession.get(journal);
            if (existing != null) {
                existing.requestedGeneration++;
                return;
            }
            if (workBySession.size() >= maxPendingSessions) {
                recordFailure(new IllegalStateException("Session journal maintenance queue is full"));
                return;
            }
            work = new Work();
            workBySession.put(journal, work);
        }
        submitDispatch(journal, work);
    }

    private void submitDispatch(SessionJournal journal, Work work) {
        try {
            dispatcher.execute(() -> dispatch(journal, work));
        } catch (RuntimeException e) {
            synchronized (this) {
                workBySession.remove(journal, work);
                notifyAll();
            }
            recordFailure(e);
        }
    }

    private void dispatch(SessionJournal journal, Work work) {
        synchronized (this) {
            if (!accepting || workBySession.get(journal) != work) {
                workBySession.remove(journal, work);
                notifyAll();
                return;
            }
        }
        try {
            executor.execute(() -> run(journal, work));
        } catch (RuntimeException e) {
            synchronized (this) {
                workBySession.remove(journal, work);
                notifyAll();
            }
            recordFailure(e);
        }
    }

    private void run(SessionJournal journal, Work work) {
        long attemptedGeneration;
        synchronized (this) {
            if (!accepting || workBySession.get(journal) != work) {
                workBySession.remove(journal, work);
                notifyAll();
                return;
            }
            work.running = true;
            work.thread = Thread.currentThread();
            attemptedGeneration = work.requestedGeneration;
            activeTasks++;
        }
        try {
            journal.performMaintenance(this::isAccepting);
        } catch (Exception e) {
            recordFailure(e);
        } finally {
            boolean redispatch;
            synchronized (this) {
                redispatch = accepting
                        && workBySession.get(journal) == work
                        && work.requestedGeneration > attemptedGeneration;
                if (!redispatch) {
                    workBySession.remove(journal, work);
                }
                work.running = false;
                work.thread = null;
                activeTasks--;
                notifyAll();
            }
            if (redispatch) {
                submitDispatch(journal, work);
            }
        }
    }

    private synchronized boolean isAccepting() {
        return accepting;
    }

    private void recordFailure(Throwable thrown) {
        failure.compareAndSet(null, thrown);
    }

    @TestOnly
    Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    @TestOnly
    synchronized void awaitQuiescence() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!workBySession.isEmpty() || activeTasks != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("Timed out waiting for journal maintenance");
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            accepting = false;
            workBySession.entrySet().removeIf(entry -> !entry.getValue().running);
            for (Work work : workBySession.values()) {
                if (work.thread != null) {
                    work.thread.interrupt();
                }
            }
        }
        if (dispatcher != null) {
            dispatcher.shutdownNow();
        }
        boolean interrupted = false;
        synchronized (this) {
            while (activeTasks > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (dispatcher != null) {
            try {
                dispatcher.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Work {
        private boolean running;
        private long requestedGeneration = 1;
        private Thread thread;
    }
}
