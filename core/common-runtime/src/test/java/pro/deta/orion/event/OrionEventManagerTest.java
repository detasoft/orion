package pro.deta.orion.event;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;
import pro.deta.orion.util.Subscription;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrionEventManagerTest {
    @Test
    void dispatchesNestedEventsInOrderDespiteAnotherHandlerFailure() throws Exception {
        OrionEventManager events = new OrionEventManager();
        ApplicationShutdownRequestedEvent nested = new ApplicationShutdownRequestedEvent("nested");
        Thread publisher = Thread.currentThread();
        List<String> received = new CopyOnWriteArrayList<>();
        List<Thread> handlerThreads = new CopyOnWriteArrayList<>();
        CountDownLatch handled = new CountDownLatch(2);
        events.subscribe(ApplicationShutdownRequestedEvent.class, event -> {
            throw new IllegalStateException("failed subscriber");
        });
        events.subscribe(ApplicationShutdownRequestedEvent.class, event -> {
            if (event.getSource().equals("parent")) events.publish(nested);
        });
        events.subscribe(ApplicationShutdownRequestedEvent.class, event -> {
            received.add(event.getSource());
            handlerThreads.add(Thread.currentThread());
            handled.countDown();
        });

        events.onStart();
        try {
            events.publish(new ApplicationShutdownRequestedEvent("parent"));
            assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            events.onStop();
        }

        assertThat(received).containsExactly("parent", "nested");
        assertThat(handlerThreads).hasSize(2).doesNotContain(publisher);
        assertThat(handlerThreads.get(0)).isSameAs(handlerThreads.get(1));
        assertThat(nested.isProcessed()).isTrue();
    }

    @Test
    void closedSubscriptionDoesNotReceiveLaterEvents() {
        OrionEventManager events = new OrionEventManager();
        AtomicInteger received = new AtomicInteger();
        Subscription subscription = events.subscribe(ApplicationShutdownRequestedEvent.class,
                event -> received.incrementAndGet());

        events.onStart();
        try {
            events.publishAndWait(new ApplicationShutdownRequestedEvent("before close"));
            subscription.close();
            events.publishAndWait(new ApplicationShutdownRequestedEvent("after close"));
        } finally {
            subscription.close();
            events.onStop();
        }

        assertThat(received).hasValue(1);
    }
}
