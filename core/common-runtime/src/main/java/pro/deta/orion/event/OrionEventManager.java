package pro.deta.orion.event;

import com.lmax.disruptor.TimeoutException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.disruptor.OrionDisruptor;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Application-wide asynchronous event bus for Orion events.
 *
 * <p>An event is a concrete {@link OrionEvent} subclass that describes something that already happened or must be
 * reacted to elsewhere in the application. Application shutdown requests use this boundary. Events should carry
 * the data their handlers need, because handlers run later and outside the original request flow.</p>
 *
 * <p>The manager owns a single root Disruptor and starts/stops it with the application lifecycle. Event dispatch is
 * delegated to the shared registry, which matches the concrete event class and source.</p>
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
    private final OrionEventRegistry registry;
    private final ArrayDeque<OrionEvent> pendingEvents = new ArrayDeque<>();
    private volatile Thread dispatchThread;

    @Inject
    public OrionEventManager(OrionEventRegistry registry) {
        this.registry = registry;
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
                        registry.dispatch(event);
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
}
