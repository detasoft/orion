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

