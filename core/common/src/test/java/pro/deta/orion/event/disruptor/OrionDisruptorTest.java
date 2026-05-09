package pro.deta.orion.event.disruptor;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.type.RequestToAclUpdate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionDisruptorTest {
    @Test
    void rejectsEventsPublishedBeforeStart() {
        OrionDisruptor disruptor = new OrionDisruptor(16);

        assertThatThrownBy(() -> disruptor.publish(new RequestToAclUpdate("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before event disruptor is started");
    }

    @Test
    void publishesEventsAfterStart() throws Exception {
        OrionDisruptor disruptor = new OrionDisruptor(16);
        CountDownLatch handled = new CountDownLatch(1);
        disruptor.handleEventsWith((eventHolder, sequence, endOfBatch) -> handled.countDown());

        disruptor.start();
        try {
            disruptor.publish(new RequestToAclUpdate("test"));

            assertThat(handled.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            disruptor.stop();
        }
    }
}
