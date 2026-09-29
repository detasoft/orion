package pro.deta.orion.event.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Orion event")
class OrionEventTest {
    @Test
    @DisplayName("records creation time")
    void recordsCreationTime() {
        Instant beforeCreation = Instant.now();

        ApplicationShutdownRequestedEvent event = new ApplicationShutdownRequestedEvent("test");

        assertThat(event.getCreatedAt()).isBetween(beforeCreation, Instant.now());
    }

    @Test
    @DisplayName("prints base event state and payload")
    void printsBaseEventStateAndPayload() {
        ApplicationShutdownRequestedEvent event = new ApplicationShutdownRequestedEvent("test-request");

        assertThat(event.toString())
                .startsWith("ApplicationShutdownRequestedEvent{")
                .contains("createdAt=", "processed=false", "source='test-request'");

        event.setProcessed();

        assertThat(event.toString()).contains("processed=true");
    }

    @Test
    @DisplayName("prints shutdown request source")
    void printsShutdownRequestSource() {
        ApplicationShutdownRequestedEvent event = new ApplicationShutdownRequestedEvent("http-admin");

        assertThat(event.toString())
                .startsWith("ApplicationShutdownRequestedEvent{")
                .contains("source='http-admin'");
    }

}
