package pro.deta.orion.event.disruptor;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionDisruptorTest {
    @Test
    void rejectsEventsPublishedBeforeStart() {
        OrionDisruptor disruptor = new OrionDisruptor(16);

        assertThatThrownBy(() -> disruptor.publish(new ApplicationShutdownRequestedEvent("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event disruptor is stopped");
    }

    @Test
    void publishesEventsAfterStart() throws Exception {
        OrionDisruptor disruptor = new OrionDisruptor(16);
        CountDownLatch handled = new CountDownLatch(1);
        disruptor.handleEventsWith((eventHolder, sequence, endOfBatch) -> handled.countDown());

        disruptor.start();
        try {
            disruptor.publish(new ApplicationShutdownRequestedEvent("test"));

            assertThat(handled.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            disruptor.stop();
        }
    }

    @Test
    void publishesBeyondRingCapacityWhileAHandlerIsBlockedAndDrainsOnStop() throws Exception {
        OrionDisruptor disruptor = new OrionDisruptor(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> handled = new CopyOnWriteArrayList<>();
        disruptor.handleEventsWith((holder, sequence, endOfBatch) -> {
            ApplicationShutdownRequestedEvent event = (ApplicationShutdownRequestedEvent) holder.getOrionEvent();
            if (event.getSource().equals("0")) {
                entered.countDown();
                release.await();
            }
            handled.add(event.getSource());
        });

        disruptor.start();
        try {
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                try {
                    disruptor.publish(new ApplicationShutdownRequestedEvent("0"));
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    Future<?> publication = executor.submit(() -> {
                        for (int number = 1; number <= 8; number++) {
                            disruptor.publish(new ApplicationShutdownRequestedEvent(Integer.toString(number)));
                        }
                    });

                    publication.get(5, TimeUnit.SECONDS);
                    assertThat(disruptor.getUnprocessedLength()).isEqualTo(9);
                } finally {
                    release.countDown();
                }
            }
        } finally {
            disruptor.stop();
        }

        assertThat(handled).containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(disruptor.getUnprocessedLength()).isZero();
    }
}
