package pro.deta.orion.agent.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class SessionReconciliationPublisherTest {
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final AgentId OTHER_AGENT = new AgentId("agent-2");
    private static final AgentInstanceId INSTANCE =
            new AgentInstanceId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f"));
    private static final AgentLaunchId LAUNCH =
            new AgentLaunchId(UUID.fromString("20010203-0405-0607-0809-0a0b0c0d0e0f"));
    private static final SessionId FIRST = new SessionId("session-1");
    private static final SessionId SECOND = new SessionId("session-2");

    @TempDir
    Path temporaryDirectory;

    @Test
    void requestsAndConsumesSessionListWhilePassingOtherMessagesDownstream() throws Exception {
        try (FileSystemSessionRegistry registry = registry()) {
            List<AgentMessage> downstreamMessages = new ArrayList<>();
            TestConnection connection = new TestConnection();
            SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                    registry, ignored -> recordingSession(downstreamMessages, new ArrayList<>()));
            AgentControlHandler.Session session = publisher.publish(context("connection-1", connection));
            authenticate(session);
            AgentMessage.Heartbeat heartbeat = heartbeat();

            session.onMessage(new AgentMessage.SessionList(List.of(descriptor(FIRST, "running"))));
            session.onMessage(heartbeat);

            assertThat(connection.sent).containsExactly(new AgentMessage.RequestSessionList());
            assertThat(registry.owns(AGENT, FIRST)).isTrue();
            assertThat(downstreamMessages).containsExactly(heartbeat);
        }
    }

    @Test
    void consumesSessionStatusAsASingletonDurableReconciliation() throws Exception {
        SessionDescriptor updated = descriptor(FIRST, "exited");
        try (FileSystemSessionRegistry registry = registry()) {
            List<AgentMessage> downstreamMessages = new ArrayList<>();
            TestConnection connection = new TestConnection();
            SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                    registry, ignored -> recordingSession(downstreamMessages, new ArrayList<>()));
            AgentControlHandler.Session session = publisher.publish(context("connection-1", connection));
            authenticate(session);
            SessionDescriptor initial = descriptor(FIRST, "running");
            registry.reconcile(AGENT, List.of(initial));

            session.onMessage(new AgentMessage.SessionStatus(updated));

            assertThat(registry.find(FIRST)).get().extracting(record -> record.reported())
                    .isEqualTo(updated);
            assertThat(downstreamMessages).isEmpty();
            assertThat(connection.closed).isFalse();
        }
        try (FileSystemSessionRegistry recovered = registry()) {
            assertThat(recovered.find(FIRST)).get().extracting(record -> record.reported())
                    .isEqualTo(updated);
        }
    }

    @Test
    void connectionTakeoverIgnoresOldReportAndReconcilesReplacementReport() throws Exception {
        try (FileSystemSessionRegistry registry = registry()) {
            SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                    registry, ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));
            TestConnection firstConnection = new TestConnection();
            TestConnection secondConnection = new TestConnection();
            AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(publisher::publish);
            AgentControlHandler.Session first = connections.activate(context("connection-1", firstConnection));
            authenticate(first);
            AgentControlHandler.Session second = connections.activate(
                    context("connection-2", secondConnection));
            authenticate(second);

            first.onMessage(new AgentMessage.SessionStatus(descriptor(FIRST, "obsolete")));
            second.onMessage(new AgentMessage.SessionStatus(descriptor(SECOND, "current")));

            assertThat(firstConnection.closed).isTrue();
            assertThat(firstConnection.sent).containsExactly(new AgentMessage.RequestSessionList());
            assertThat(secondConnection.sent).containsExactly(new AgentMessage.RequestSessionList());
            assertThat(registry.find(FIRST)).isEmpty();
            assertThat(registry.owns(AGENT, SECOND)).isTrue();
        }
    }

    @Test
    void foreignOwnershipClaimClosesConnectionWithoutChangingRegistry() throws Exception {
        try (FileSystemSessionRegistry registry = registry()) {
            SessionDescriptor owned = descriptor(FIRST, "owned elsewhere");
            registry.reconcile(OTHER_AGENT, List.of(owned));
            TestConnection connection = new TestConnection();
            SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                    registry, ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));
            AgentControlHandler.Session session = publisher.publish(context("connection-1", connection));
            authenticate(session);

            session.onMessage(new AgentMessage.SessionList(List.of(
                    descriptor(SECOND, "new"),
                    descriptor(FIRST, "foreign"))));

            assertThat(connection.closed).isTrue();
            assertThat(registry.find(SECOND)).isEmpty();
            assertThat(registry.find(FIRST)).get().extracting(record -> record.agentId())
                    .isEqualTo(OTHER_AGENT);
        }
    }

    @Test
    void foreignSessionStatusClosesConnectionWithoutChangingRegistry() throws Exception {
        try (FileSystemSessionRegistry registry = registry()) {
            SessionDescriptor owned = descriptor(FIRST, "owned elsewhere");
            registry.reconcile(OTHER_AGENT, List.of(owned));
            TestConnection connection = new TestConnection();
            SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                    registry, ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));
            AgentControlHandler.Session session = publisher.publish(context("connection-1", connection));
            authenticate(session);

            session.onMessage(new AgentMessage.SessionStatus(descriptor(FIRST, "foreign update")));

            assertThat(connection.closed).isTrue();
            assertThat(registry.find(FIRST)).get().extracting(record -> record.reported())
                    .isEqualTo(owned);
        }
    }

    @Test
    void durableRegistryFailureClosesConnection() throws Exception {
        FileSystemSessionRegistry registry = registry();
        TestConnection connection = new TestConnection();
        SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                registry, ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));
        AgentControlHandler.Session session = publisher.publish(context("connection-1", connection));
        authenticate(session);
        registry.close();

        session.onMessage(new AgentMessage.SessionStatus(descriptor(FIRST, "late")));

        assertThat(connection.closed).isTrue();
    }

    @Test
    void failedSessionListRequestClosesOnlyItsConnection() throws Exception {
        try (FileSystemSessionRegistry registry = registry()) {
            TestConnection failed = new TestConnection();
            failed.sendFailure = new IllegalStateException("request failed");
            TestConnection healthy = new TestConnection();
            SessionReconciliationPublisher publisher = new SessionReconciliationPublisher(
                    registry, ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));

            authenticate(publisher.publish(context("connection-1", failed)));
            authenticate(publisher.publish(context("connection-2", healthy)));

            assertThat(failed.closed).isTrue();
            assertThat(healthy.closed).isFalse();
            assertThat(healthy.sent).containsExactly(new AgentMessage.RequestSessionList());
        }
    }

    private FileSystemSessionRegistry registry() throws Exception {
        return new FileSystemSessionRegistry(temporaryDirectory.resolve("sessions"));
    }

    private static void authenticate(AgentControlHandler.Session session) {
        ((AuthenticatedSession) session).onAuthenticated();
    }

    private static AuthenticatedConnectionContext context(String connectionId, TestConnection connection) {
        return new AuthenticatedConnectionContext(
                AGENT,
                new AgentGeneration(1),
                LAUNCH,
                INSTANCE,
                "1.0.0",
                new MachineInfo("worker", "linux", "aarch64"),
                Map.of(),
                new ConnectionId(connectionId),
                connection,
                () -> AuthenticatedConnectionContext.RenewalResult.RENEWED,
                (version, machine, capabilities, observedAt) ->
                        AuthenticatedConnectionContext.ObservationResult.RECORDED);
    }

    private static AgentMessage.Heartbeat heartbeat() {
        long epochMillis = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli();
        return new AgentMessage.Heartbeat(AGENT, INSTANCE, epochMillis);
    }

    private static SessionDescriptor descriptor(SessionId sessionId, String detail) {
        return new SessionDescriptor(
                sessionId,
                AgentMessage.SessionState.RUNNING,
                Optional.of(new EventId(1)),
                Optional.of(new EventId(2)),
                detail);
    }

    private static AgentControlHandler.Session recordingSession(
            List<AgentMessage> messages, List<Throwable> closures) {
        return new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
                messages.add(message);
            }

            @Override
            public void onClosed(Throwable failure) {
                closures.add(failure);
            }
        };
    }

    private static final class TestConnection implements AgentControlHandler.Connection {
        private final List<AgentMessage> sent = new ArrayList<>();
        private RuntimeException sendFailure;
        private boolean closed;

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            sent.add(message);
            return sendFailure == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(sendFailure);
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
