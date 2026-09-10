package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.agent.protocol.AgentMessage.SessionState.DEGRADED;
import static pro.deta.orion.agent.protocol.AgentMessage.SessionState.EXITED;
import static pro.deta.orion.agent.protocol.AgentMessage.SessionState.FAILED;
import static pro.deta.orion.agent.protocol.AgentMessage.SessionState.RUNNING;

class FileSystemSessionRegistryTest {
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final AgentId OTHER_AGENT = new AgentId("agent-2");
    private static final SessionId SESSION = new SessionId("session-1");
    private static final SessionId OTHER_SESSION = new SessionId("session-2");

    @TempDir
    Path root;

    @Test
    void discoversSessionsIdempotentlyAndRestoresThemAfterRestart() throws Exception {
        SessionDescriptor running = descriptor(SESSION, RUNNING, 1, 4, "live");
        SessionDescriptor completed = descriptor(OTHER_SESSION, EXITED, 1, 8, "exit 0");
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root)) {
            registry.reconcile(AGENT, List.of(running, completed));
            registry.reconcile(AGENT, List.of(running, completed));

            assertThat(registry.ownedBy(AGENT))
                    .extracting(SessionRecord::descriptor)
                    .containsExactly(running, completed);
            assertThat(registry.owns(AGENT, SESSION)).isTrue();
            assertThat(registry.owns(OTHER_AGENT, SESSION)).isFalse();
            assertThat(registry.owns(AGENT, new SessionId("unknown"))).isFalse();
        }

        try (FileSystemSessionRegistry reopened = new FileSystemSessionRegistry(root)) {
            assertThat(reopened.find(SESSION)).contains(new SessionRecord(
                    AGENT, running, Optional.empty()));
            assertThat(reopened.ownedBy(AGENT)).hasSize(2);
        }
    }

    @Test
    void missingSessionsRemainOwnedAndReportedMetadataCanAdvance() throws Exception {
        SessionDescriptor initial = descriptor(SESSION, RUNNING, 1, 4, "live");
        SessionDescriptor degraded = descriptor(OTHER_SESSION, DEGRADED, 3, 9, "host unavailable");
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root)) {
            registry.reconcile(AGENT, List.of(initial));
            registry.reconcile(AGENT, List.of(degraded));

            assertThat(registry.find(SESSION).orElseThrow().descriptor()).isEqualTo(initial);
            assertThat(registry.find(OTHER_SESSION).orElseThrow().descriptor()).isEqualTo(degraded);
        }
    }

    @Test
    void foreignSessionRejectsTheCompleteReportBeforeFreshSessionsAreStored() throws Exception {
        SessionDescriptor owned = descriptor(SESSION, RUNNING, 1, 2, "owned");
        SessionDescriptor fresh = descriptor(OTHER_SESSION, RUNNING, 1, 1, "fresh");
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root)) {
            registry.reconcile(AGENT, List.of(owned));

            assertThatThrownBy(() -> registry.reconcile(OTHER_AGENT, List.of(fresh, owned)))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.CONFLICT);
            assertThat(registry.find(OTHER_SESSION)).isEmpty();
            assertThat(registry.find(SESSION).orElseThrow().agentId()).isEqualTo(AGENT);
        }
    }

    @Test
    void authoritativeOutcomeSurvivesLaterTransientReports() throws Exception {
        SessionDescriptor initial = descriptor(SESSION, RUNNING, 1, 4, "live");
        SessionDescriptor stale = descriptor(SESSION, RUNNING, 2, 7, "stale process view");
        SessionRecord.Outcome outcome = new SessionRecord.Outcome(FAILED, "exit 17");
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root)) {
            registry.reconcile(AGENT, List.of(initial));
            registry.recordOutcome(AGENT, SESSION, outcome);
            registry.reconcile(AGENT, List.of(stale));

            SessionRecord record = registry.find(SESSION).orElseThrow();
            assertThat(record.reported()).isEqualTo(stale);
            assertThat(record.outcome()).contains(outcome);
            assertThat(record.descriptor()).isEqualTo(descriptor(SESSION, FAILED, 2, 7, "exit 17"));
        }
    }

    @Test
    void outcomeRequiresAKnownOwnedSessionAndTerminalState() throws Exception {
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root)) {
            assertThatThrownBy(() -> registry.recordOutcome(
                    AGENT, SESSION, new SessionRecord.Outcome(EXITED, "exit 0")))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.NOT_FOUND);
            assertThatThrownBy(() -> new SessionRecord.Outcome(RUNNING, "not terminal"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static SessionDescriptor descriptor(
            SessionId sessionId,
            AgentMessage.SessionState state,
            long first,
            long last,
            String detail) {
        return new SessionDescriptor(
                sessionId, state, Optional.of(new EventId(first)), Optional.of(new EventId(last)), detail);
    }
}
