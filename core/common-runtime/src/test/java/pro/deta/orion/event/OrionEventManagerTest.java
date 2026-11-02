package pro.deta.orion.event;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrionEventManagerTest {
    @Test
    void dispatchesEachTargetOnceInRegistrationOrder() {
        OrionEventManager events = new OrionEventManager();
        List<Integer> received = new CopyOnWriteArrayList<>();
        for (int target : List.of(3, 1, 2)) {
            events.forTarget(target).subscribe(ApplicationShutdownRequestedEvent.class, null,
                    event -> received.add(target));
        }
        OrionEvents first = events.forTarget(3);
        first.subscribe(ApplicationShutdownRequestedEvent.class, null, event -> received.add(99));
        events.onStart();
        try {
            events.publishAndWait(new ApplicationShutdownRequestedEvent("first"));
            assertThat(received).containsExactly(3, 1, 2);
            received.clear();
            first.unsubscribe(ApplicationShutdownRequestedEvent.class, null);
            first.subscribe(ApplicationShutdownRequestedEvent.class, null, event -> received.add(3));
            events.publishAndWait(new ApplicationShutdownRequestedEvent("second"));
            assertThat(received).containsExactly(1, 2, 3);
        } finally {
            events.onStop();
        }
    }

    @Test
    void dispatchesNestedEventsInOrderDespiteAnotherHandlerFailure() throws Exception {
        OrionEventManager events = new OrionEventManager();
        ApplicationShutdownRequestedEvent nested = new ApplicationShutdownRequestedEvent("nested");
        Thread publisher = Thread.currentThread();
        List<String> received = new CopyOnWriteArrayList<>();
        List<Thread> handlerThreads = new CopyOnWriteArrayList<>();
        CountDownLatch handled = new CountDownLatch(2);
        events.forTarget(new Object()).subscribe(ApplicationShutdownRequestedEvent.class, null, event -> {
            throw new IllegalStateException("failed subscriber");
        });
        events.forTarget(new Object()).subscribe(ApplicationShutdownRequestedEvent.class, null, event -> {
            if (event.getSource().equals("parent")) events.publish(nested);
        });
        events.forTarget(new Object()).subscribe(ApplicationShutdownRequestedEvent.class, null, event -> {
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
        OrionEvents subscription = events.forTarget(new Object());
        subscription.subscribe(ApplicationShutdownRequestedEvent.class, null, event -> received.incrementAndGet());

        events.onStart();
        try {
            events.publishAndWait(new ApplicationShutdownRequestedEvent("before close"));
            subscription.unsubscribe(ApplicationShutdownRequestedEvent.class, null);
            events.publishAndWait(new ApplicationShutdownRequestedEvent("after close"));
        } finally {
            subscription.unsubscribe(ApplicationShutdownRequestedEvent.class, null);
            events.onStop();
        }

        assertThat(received).hasValue(1);
    }
}
