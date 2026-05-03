package pro.deta.orion.event.disruptor;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import pro.deta.orion.event.type.OrionEvent;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Small project-level wrapper around LMAX Disruptor for asynchronous Orion events.
 *
 * <p>The Disruptor reuses {@link EventHolder} instances in the ring buffer, while each publish call places a
 * fresh {@link OrionEvent} into the claimed holder. Keeping the wrapper small makes the event manager independent
 * from the low-level ring-buffer API and keeps lifecycle operations in one place.</p>
 *
 * <p>An Orion event is a small domain notification object, for example "a git receive finished" or "ACL data should be
 * reloaded". This class does not interpret those events. It only moves them from publishing threads into the asynchronous
 * event-processing thread through a bounded ring buffer.</p>
 */
public class OrionDisruptor {
    private final Disruptor<EventHolder> eventHolderDisruptor;

    public OrionDisruptor(int size) {
        eventHolderDisruptor = new Disruptor<>(EventHolder::new, size, DaemonThreadFactory.INSTANCE);
    }

    public void start() {
        eventHolderDisruptor.start();
    }

    public void publish(OrionEvent orionEvent) {
        publish(() -> orionEvent);
    }

    public void publish(Supplier<OrionEvent> eventPublisher) {
        eventHolderDisruptor.getRingBuffer().publishEvent(new EventTranslator<EventHolder>() {
            @Override
            public void translateTo(EventHolder eventHolder, long l) {
                eventHolder.setOrionEvent(eventPublisher.get());
            }
        });
    }

    public void stop() throws TimeoutException {
        eventHolderDisruptor.shutdown(10, TimeUnit.SECONDS);
    }

    public void handleEventsWith(EventHandler<EventHolder> eventHandler) {
        eventHolderDisruptor.handleEventsWith(eventHandler);
    }

    public long getUnprocessedLength() {
        RingBuffer<EventHolder> ringBuffer = eventHolderDisruptor.getRingBuffer();
        long cursor = ringBuffer.getCursor();
        long minConsumerSequence = ringBuffer.getMinimumGatingSequence();

        return cursor - minConsumerSequence;
    }
}
