package pro.deta.orion.agent.server.replication;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.SessionId;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LiveEventBrokerTest {
    @Test
    void coalescesSlowReaderSignalsWithoutWakingAnotherSession() throws Exception {
        LiveEventBroker broker = new LiveEventBroker();
        SessionId first = new SessionId("first");
        try (var firstReader = broker.subscribe(first);
             var secondReader = broker.subscribe(new SessionId("second"))) {
            for (int index = 0; index < 1_000; index++) {
                broker.publish(first);
            }
            assertThat(firstReader.awaitChange(Duration.ZERO)).isTrue();
            assertThat(firstReader.awaitChange(Duration.ZERO)).isFalse();
            assertThat(secondReader.awaitChange(Duration.ZERO)).isFalse();
        }
        broker.close();
    }
}
