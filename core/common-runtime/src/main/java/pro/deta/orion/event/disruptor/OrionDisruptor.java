package pro.deta.orion.event.disruptor;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Feeds asynchronous Orion events into a bounded Disruptor ring buffer.
 * Publishers append to an unbounded ingress queue so they never wait for handlers while holding domain locks.
 * One feeder preserves queue order and waits for ring-buffer capacity. Shutdown drains ingress before the ring.
 * Sustained publication faster than dispatch consumes heap; events are neither dropped nor merged.
 */
@Slf4j
public class OrionDisruptor {
    private static final Publication STOP = new Publication(null);
    private final Disruptor<EventHolder> eventHolderDisruptor;
    private final BlockingQueue<Publication> publications = new LinkedBlockingQueue<>();
    private final AtomicLong unprocessed = new AtomicLong();
    // AI_TODO - remove - no need to have a dedicated thread to copy published events, it should directly publish to the disruptor
    private final Thread publisher;
    private volatile boolean started;

    public OrionDisruptor(int size) {
        eventHolderDisruptor = new Disruptor<>(EventHolder::new, size, DaemonThreadFactory.INSTANCE);
        publisher = Thread.ofPlatform().daemon(true).name("orion-event-publisher").unstarted(this::publishQueuedEvents);
    }

    public synchronized void start() {
        eventHolderDisruptor.start();
        publisher.start();
        started = true;
    }

    public synchronized void publish(OrionEvent event) {
        Objects.requireNonNull(event, "event");
        if (!started) {
            throw new IllegalStateException("Cannot publish Orion event while event disruptor is stopped");
        }
        unprocessed.incrementAndGet();
        publications.add(new Publication(event));
    }

    public void publish(Supplier<OrionEvent> eventPublisher) {
        publish(Objects.requireNonNull(eventPublisher, "eventPublisher").get());
    }

    private void publishQueuedEvents() {
        try {
            while (true) {
                Publication publication = publications.take();
                if (publication == STOP) return;
                eventHolderDisruptor.getRingBuffer().publishEvent(
                        (holder, sequence) -> holder.setOrionEvent(publication.event()));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.error("Event publisher interrupted before ingress was drained", failure);
        }
    }

    public void stop() throws TimeoutException {
        synchronized (this) {
            if (!started) return;
            started = false;
            publications.add(STOP);
        }
        boolean interrupted = false;
        while (publisher.isAlive()) {
            try {
                publisher.join();
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        try {
            eventHolderDisruptor.shutdown(10, TimeUnit.SECONDS);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void handleEventsWith(EventHandler<EventHolder> eventHandler) {
        eventHolderDisruptor.handleEventsWith((holder, sequence, endOfBatch) -> {
            try {
                eventHandler.onEvent(holder, sequence, endOfBatch);
            } finally {
                unprocessed.decrementAndGet();
            }
        });
    }

    public long getUnprocessedLength() {
        return unprocessed.get();
    }

    // AI_TODO - remove, unnecessary
    private record Publication(OrionEvent event) {}
}
