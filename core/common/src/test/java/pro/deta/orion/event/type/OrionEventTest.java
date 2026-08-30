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

        RequestToAclUpdate event = new RequestToAclUpdate("test");

        assertThat(event.getCreatedAt()).isBetween(beforeCreation, Instant.now());
    }

    @Test
    @DisplayName("prints base event state and payload")
    void printsBaseEventStateAndPayload() {
        RequestToAclUpdate event = new RequestToAclUpdate("acl-refresh");

        assertThat(event.toString())
                .startsWith("RequestToAclUpdate{")
                .contains("createdAt=", "processed=false", "initiator='acl-refresh'");

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
