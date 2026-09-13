package pro.deta.orion.agent.server.replication;

import pro.deta.orion.agent.protocol.SessionId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Coalesces durable journal changes; subscribers always replay records from storage. */
public final class LiveEventBroker implements AutoCloseable {
    private final Map<SessionId, List<Subscription>> subscriptions = new HashMap<>();
    private boolean closed;

    public synchronized Subscription subscribe(SessionId sessionId) {
        if (closed) {
            throw new IllegalStateException("Live event broker is closed");
        }
        Subscription subscription = new Subscription(this, Objects.requireNonNull(sessionId, "sessionId"));
        subscriptions.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(subscription);
        return subscription;
    }

    synchronized void publish(SessionId sessionId) {
        if (closed) {
            return;
        }
        for (Subscription subscription : subscriptions.getOrDefault(sessionId, List.of())) {
            subscription.signal();
        }
    }

    private synchronized void remove(Subscription subscription) {
        List<Subscription> sessionSubscriptions = subscriptions.get(subscription.sessionId);
        if (sessionSubscriptions != null) {
            sessionSubscriptions.remove(subscription);
            if (sessionSubscriptions.isEmpty()) {
                subscriptions.remove(subscription.sessionId);
            }
        }
        subscription.stop();
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (List<Subscription> sessionSubscriptions : subscriptions.values()) {
            for (Subscription subscription : sessionSubscriptions) {
                subscription.stop();
            }
        }
        subscriptions.clear();
    }

    public static final class Subscription implements AutoCloseable {
        private final LiveEventBroker broker;
        private final SessionId sessionId;
        private final ArrayBlockingQueue<Boolean> notifications = new ArrayBlockingQueue<>(1);
        private volatile boolean closed;

        private Subscription(LiveEventBroker broker, SessionId sessionId) {
            this.broker = broker;
            this.sessionId = sessionId;
        }

        public boolean awaitChange(Duration timeout) throws InterruptedException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            return !closed && notifications.poll(timeout.toNanos(), TimeUnit.NANOSECONDS) != null && !closed;
        }

        private void signal() {
            if (!closed) {
                notifications.offer(Boolean.TRUE);
            }
        }

        private void stop() {
            closed = true;
            notifications.offer(Boolean.TRUE);
        }

        @Override
        public void close() {
            broker.remove(this);
        }
    }
}
