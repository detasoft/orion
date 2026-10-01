package pro.deta.orion.agentd.core;

import pro.deta.orion.agent.protocol.SessionId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class SessionCommandLanes implements AutoCloseable {
    private final int maxSessions;
    private final int maxCommandsPerSession;
    private final ThreadPoolExecutor workers;
    private final Map<SessionId, Lane> lanes = new HashMap<>();
    private boolean connected;
    private boolean closed;

    SessionCommandLanes(int parallelism, int maxSessions, int maxCommandsPerSession) {
        if (parallelism < 1 || maxSessions < parallelism || maxCommandsPerSession < 1) {
            throw new IllegalArgumentException("invalid command lane limits");
        }
        this.maxSessions = maxSessions;
        this.maxCommandsPerSession = maxCommandsPerSession;
        this.workers = new ThreadPoolExecutor(
                parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxSessions),
                runnable -> {
                    Thread thread = new Thread(runnable, "agentd-command-lane");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    synchronized void connected() {
        if (!closed) {
            connected = true;
        }
    }

    void disconnected() {
        List<Task<?>> discarded;
        synchronized (this) {
            connected = false;
            discarded = removeQueued();
        }
        for (Task<?> task : discarded) {
            task.discard(Reason.DISCONNECTED);
        }
    }

    synchronized <T> CompletionStage<Outcome<T>> submit(SessionId sessionId, Callable<T> action) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(action, "action");
        if (closed) {
            return rejected(Reason.CLOSED);
        }
        if (!connected) {
            return rejected(Reason.DISCONNECTED);
        }
        Lane lane = lanes.get(sessionId);
        if (lane == null) {
            if (lanes.size() == maxSessions) {
                return rejected(Reason.CAPACITY);
            }
            lane = new Lane();
            lanes.put(sessionId, lane);
        }
        if (lane.size == maxCommandsPerSession) {
            return rejected(Reason.CAPACITY);
        }
        Task<T> task = new Task<>(action);
        lane.queued.addLast(task);
        lane.size++;
        if (!lane.draining) {
            lane.draining = true;
            Lane selected = lane;
            workers.execute(() -> drain(sessionId, selected));
        }
        return task.result;
    }

    private void drain(SessionId sessionId, Lane lane) {
        while (true) {
            Task<?> task;
            synchronized (this) {
                task = lane.queued.pollFirst();
                if (task == null) {
                    lane.draining = false;
                    lanes.remove(sessionId, lane);
                    return;
                }
            }
            Runnable completion = task.run();
            synchronized (this) {
                lane.size--;
            }
            completion.run();
        }
    }

    private List<Task<?>> removeQueued() {
        List<Task<?>> discarded = new ArrayList<>();
        for (Lane lane : lanes.values()) {
            Task<?> task;
            while ((task = lane.queued.pollFirst()) != null) {
                lane.size--;
                discarded.add(task);
            }
        }
        return discarded;
    }

    @Override
    public void close() {
        List<Task<?>> discarded;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            connected = false;
            discarded = removeQueued();
            workers.shutdown();
        }
        for (Task<?> task : discarded) {
            task.discard(Reason.CLOSED);
        }
        try {
            if (!workers.awaitTermination(3, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException failure) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static <T> CompletionStage<Outcome<T>> rejected(Reason reason) {
        return CompletableFuture.completedFuture(new Outcome.Discarded<>(reason));
    }

    sealed interface Outcome<T> {
        record Completed<T>(T value) implements Outcome<T> {
        }

        record Discarded<T>(Reason reason) implements Outcome<T> {
        }
    }

    enum Reason {
        CAPACITY,
        DISCONNECTED,
        CLOSED
    }

    private static final class Lane {
        private final ArrayDeque<Task<?>> queued = new ArrayDeque<>();
        private int size;
        private boolean draining;
    }

    private static final class Task<T> {
        private final Callable<T> action;
        private final CompletableFuture<Outcome<T>> result = new CompletableFuture<>();

        private Task(Callable<T> action) {
            this.action = action;
        }

        private Runnable run() {
            try {
                T value = action.call();
                return () -> result.complete(new Outcome.Completed<>(value));
            } catch (Exception failure) {
                return () -> result.completeExceptionally(failure);
            }
        }

        private void discard(Reason reason) {
            result.complete(new Outcome.Discarded<>(reason));
        }
    }
}
