package pro.deta.orion.event.disruptor;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Publishes asynchronous Orion events directly into a bounded, multi-producer Disruptor ring buffer.
 * Publishers wait for capacity; callers must release domain locks needed by handlers before publishing.
 * The lifecycle lock admits concurrent publishers and excludes shutdown until their publications complete.
 */
public class OrionDisruptor {
    private final Disruptor<EventHolder> eventHolderDisruptor;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean started;

    public OrionDisruptor(int size) {
        eventHolderDisruptor = new Disruptor<>(EventHolder::new, size, DaemonThreadFactory.INSTANCE);
    }

    public void start() {
        lifecycle.writeLock().lock();
        try {
            eventHolderDisruptor.start();
            started = true;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public void publish(OrionEvent event) {
        Objects.requireNonNull(event, "event");
        lifecycle.readLock().lock();
        try {
            if (!started) {
                throw new IllegalStateException("Cannot publish Orion event while event disruptor is stopped");
            }
            eventHolderDisruptor.getRingBuffer().publishEvent(
                    (holder, sequence, published) -> holder.setOrionEvent(published), event);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void publish(Supplier<OrionEvent> eventPublisher) {
        publish(Objects.requireNonNull(eventPublisher, "eventPublisher").get());
    }

    public void stop() throws TimeoutException {
        lifecycle.writeLock().lock();
        try {
            if (!started) return;
            started = false;
        } finally {
            lifecycle.writeLock().unlock();
        }
        eventHolderDisruptor.shutdown(10, TimeUnit.SECONDS);
    }

    public void handleEventsWith(EventHandler<EventHolder> eventHandler) {
        eventHolderDisruptor.handleEventsWith(eventHandler);
    }
}
