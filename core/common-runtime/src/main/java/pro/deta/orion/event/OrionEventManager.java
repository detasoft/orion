package pro.deta.orion.event;

import com.lmax.disruptor.TimeoutException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.disruptor.OrionDisruptor;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application-wide asynchronous event bus for Orion events.
 *
 * <p>An event is a concrete {@link OrionEvent} subclass that describes something that already happened or must be
 * reacted to elsewhere in the application. Application shutdown requests use this boundary. Events should carry
 * the data their handlers need, because handlers run later and outside the original request flow.</p>
 *
 * <p>The manager owns a single root Disruptor and starts/stops it with the application lifecycle. Event dispatch is
 * based on matching the concrete event class and source. Handlers for the same route run in
 * registration order.</p>
 *
 * <p>Handlers run on the Disruptor daemon thread, so they should be quick and should not rely on request-thread
 * ThreadLocals. If a handler needs request context, the publisher must copy the required data into the event object.</p>
 *
 * <p>External publishers write directly to the ring buffer and wait when it is full. Events published
 * by a handler enter a dispatcher-local queue, drained before the next ring-buffer event.</p>
 */
@Singleton
@Slf4j
public class OrionEventManager implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    public static final int RING_BUFFER_SIZE = 32_768;
    private final OrionDisruptor rootDisruptor = new OrionDisruptor(RING_BUFFER_SIZE);
    private final ConcurrentHashMap<Route, Map<Object, Consumer<OrionEvent>>> registrations
            = new ConcurrentHashMap<>();
    private final ArrayDeque<OrionEvent> pendingEvents = new ArrayDeque<>();
    private volatile Thread dispatchThread;

    @Inject
    public OrionEventManager() {}

    public OrionEvents forTarget(Object target) {
        return new OrionEvents(this, Objects.requireNonNull(target, "target"));
    }

    @Override
    public void onStart() {
        rootDisruptor.handleEventsWith((holder, sequence, endOfBatch) -> {
            dispatchThread = Thread.currentThread();
            try {
                pendingEvents.addLast(holder.getOrionEvent());
                while (!pendingEvents.isEmpty()) {
                    OrionEvent event = pendingEvents.removeFirst();
                    try {
                        dispatch(event);
                    } finally {
                        event.setProcessed();
                    }
                }
            } finally {
                holder.setOrionEvent(null);
            }
        });
        rootDisruptor.start();
    }

    public void onStop() {
        try {
            rootDisruptor.stop();
        } catch (TimeoutException e) {
            log.error("Error while stopping Disruptor: ",e);
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return rootDisruptor.isStarted();
    }

    public void publish(Supplier<OrionEvent> eventPublisher) {
        publish(Objects.requireNonNull(eventPublisher, "eventPublisher").get());
    }

    public void publish(OrionEvent event) {
        Objects.requireNonNull(event, "event");
        if (Thread.currentThread() == dispatchThread) {
            pendingEvents.addLast(event);
        } else {
            rootDisruptor.publish(event);
        }
    }

    // Use only in rare cases.
    public void publishAndWait(OrionEvent event) {
        if (Thread.currentThread() == dispatchThread) {
            throw new IllegalStateException("Cannot wait for an event on the dispatcher thread");
        }
        publish(event);
        long started = System.nanoTime();
        while (!event.isProcessed()) {
            if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(5)) {
                throw new IllegalStateException("Timed out waiting for event: " + event);
            }
            LockSupport.parkNanos(100_000);
        }
    }

    <T extends OrionEvent> void subscribe(
            Class<T> type, Object source, Object target, Consumer<? super T> handler) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(handler, "handler");
        registrations.compute(new Route(type, source), (route, targets) -> {
            Map<Object, Consumer<OrionEvent>> updated = targets == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(targets);
            updated.putIfAbsent(target, event -> handler.accept(type.cast(event)));
            return updated;
        });
    }

    void unsubscribe(Object source, Object target) {
        for (Route route : registrations.keySet()) {
            if (Objects.equals(route.source(), source)) removeTarget(route, target);
        }
    }

    void unsubscribe(Class<? extends OrionEvent> type, Object source, Object target) {
        removeTarget(new Route(type, source), target);
    }

    private void removeTarget(Route route, Object target) {
        registrations.computeIfPresent(route, (key, targets) -> {
            Map<Object, Consumer<OrionEvent>> updated = new LinkedHashMap<>(targets);
            updated.remove(target);
            return updated.isEmpty() ? null : updated;
        });
    }

    private void dispatch(OrionEvent event) {
        Map<Object, Consumer<OrionEvent>> targets = registrations.get(
                new Route(event.getClass(), event.source()));
        if (targets == null) return;
        for (Consumer<OrionEvent> target : targets.values()) {
            try {
                target.accept(event);
            } catch (RuntimeException failure) {
                log.warn("Event handler failed for {}", event, failure);
            }
        }
    }

    private record Route(Class<? extends OrionEvent> type, Object source) {
        private Route {
            Objects.requireNonNull(type, "type");
        }
    }
}
