package pro.deta.orion.agentd.transport;

import org.junit.jupiter.api.Test;

import pro.deta.orion.agent.protocol.SessionId;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundQueuesTest {
    @Test
    void controlRemainsAvailableWhenTheSessionQueueIsFull() {
        OutboundQueues<String> queues = new OutboundQueues<>(1, 1);
        SessionId session = new SessionId("session-a");

        assertThat(queues.offerSession(session, "session")).isTrue();
        assertThat(queues.offerSession(session, "over-capacity")).isFalse();
        assertThat(queues.offerControl("control")).isTrue();
        assertThat(queues.poll().value()).isEqualTo("control");
        assertThat(queues.poll().value()).isEqualTo("session");
    }

    @Test
    void rotatesSessionsFairly() {
        OutboundQueues<String> queues = new OutboundQueues<>(1, 4);
        SessionId first = new SessionId("first");
        SessionId second = new SessionId("second");
        queues.offerSession(first, "first-1");
        queues.offerSession(first, "first-2");
        queues.offerSession(second, "second-1");

        assertThat(queues.poll().value()).isEqualTo("first-1");
        assertThat(queues.poll().value()).isEqualTo("second-1");
        assertThat(queues.poll().value()).isEqualTo("first-2");
    }

    @Test
    void drainsOnlyTheFailedSession() {
        OutboundQueues<String> queues = new OutboundQueues<>(1, 3);
        SessionId first = new SessionId("first");
        SessionId second = new SessionId("second");
        queues.offerSession(first, "first-1");
        queues.offerSession(second, "second-1");
        queues.offerSession(first, "first-2");

        assertThat(queues.drainSession(first)).extracting(OutboundQueues.Entry::value)
                .containsExactly("first-1", "first-2");
        assertThat(queues.poll().value()).isEqualTo("second-1");
    }

    @Test
    void skipsSessionsWithAnActiveWrite() {
        OutboundQueues<String> queues = new OutboundQueues<>(1, 3);
        SessionId first = new SessionId("first");
        SessionId second = new SessionId("second");
        queues.offerSession(first, "first-1");
        queues.offerSession(second, "second-1");

        assertThat(queues.pollSession(second::equals).value()).isEqualTo("second-1");
        assertThat(queues.pollSession(ignored -> true).value()).isEqualTo("first-1");
    }

    @Test
    void globalCapacityRejectionDoesNotLeaveAnEmptySessionQueue() {
        OutboundQueues<String> queues = new OutboundQueues<>(1, 1, 1);
        SessionId first = new SessionId("first");
        SessionId second = new SessionId("second");

        assertThat(queues.offerSession(first, "first")).isTrue();
        assertThat(queues.offerSession(second, "rejected")).isFalse();
        assertThat(queues.pollSession(ignored -> true).value()).isEqualTo("first");
        assertThat(queues.pollSession(ignored -> true)).isNull();
        assertThat(queues.offerSession(second, "second")).isTrue();
        assertThat(queues.pollSession(ignored -> true).value()).isEqualTo("second");
    }
}
