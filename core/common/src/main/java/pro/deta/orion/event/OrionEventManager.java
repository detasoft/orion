package pro.deta.orion.event;

import com.lmax.disruptor.TimeoutException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.disruptor.OrionDisruptor;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.Subscription;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * intentionally simple: handlers are registered by the concrete event class and are invoked only for that exact class.
 * There is no superclass/interface dispatch.</p>
 *
 * <p>Handlers run on the Disruptor daemon thread, so they should be quick and should not rely on request-thread
 * ThreadLocals. If a handler needs request context, the publisher must copy the required data into the event object.</p>
 *
 * <p>External publishers append to the ingress queue without waiting for ring-buffer capacity. Events published
 * by a handler enter a dispatcher-local queue, drained before the next ring-buffer event.</p>
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionEventManager implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    public static final int RING_BUFFER_SIZE = 32_768;
    private final OrionDisruptor rootDisruptor = new OrionDisruptor(RING_BUFFER_SIZE);
    private final CopyOnWriteArrayList<Handler<?>> handlers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<OrionEvent> pendingEvents = new ArrayDeque<>();
    private volatile Thread dispatchThread;
    private volatile boolean running;

    public void onInit() {
        rootDisruptor.handleEventsWith((holder, sequence, endOfBatch) -> {
            dispatchThread = Thread.currentThread();
            try {
                pendingEvents.addLast(holder.getOrionEvent());
                while (!pendingEvents.isEmpty()) {
                    OrionEvent event = pendingEvents.removeFirst();
                    try {
                        handleEvent(event);
                    } finally {
                        event.setProcessed();
                    }
                }
            } finally {
                holder.setOrionEvent(null);
                dispatchThread = null;
            }
        });
        rootDisruptor.start();
        running = true;
    }

    public long getUnprocessedLength() {
        return rootDisruptor.getUnprocessedLength();
    }

    public void onStop() {
        running = false;
        try {
            rootDisruptor.stop();
        } catch (TimeoutException e) {
            log.error("Error while stopping Disruptor: ",e);
        } finally {
            handlers.clear();
        }
    }

    @Override
    public void onStart() {
        onInit();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void handleEvent(OrionEvent event) {
        for (Handler<?> handler : handlers) {
            if (handler.type() != event.getClass()) continue;
            try {
                handler.accept(event);
            } catch (RuntimeException failure) {
                log.warn("Event handler failed for {}", event, failure);
            }
        }
    }

    public <T extends OrionEvent> Subscription subscribe(Class<T> type, Consumer<T> consumer) {
        Handler<T> handler = new Handler<>(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(consumer, "consumer"));
        handlers.add(handler);
        return () -> handlers.remove(handler);
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
        if (!OrionUtils.waitForCondition(event::isProcessed)) {
            throw new IllegalStateException("Timed out waiting for event: " + event);
        }
    }

    private static final class Handler<T extends OrionEvent> {
        private final Class<T> type;
        private final Consumer<T> consumer;

        private Handler(Class<T> type, Consumer<T> consumer) {
            this.type = type;
            this.consumer = consumer;
        }

        Class<T> type() {
            return type;
        }

        void accept(OrionEvent event) {
            consumer.accept(type.cast(event));
        }
    }
}
