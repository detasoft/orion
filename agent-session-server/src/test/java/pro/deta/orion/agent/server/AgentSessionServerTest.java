package pro.deta.orion.agent.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.provisioning.AgentdLaunchAttempt;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSessionServerTest {
    private static final AgentId AGENT_ID = new AgentId("agent-1");

    @TempDir
    Path root;

    @Test
    void composesAuthenticationConnectionOwnershipAndSessionReconciliation() throws Exception {
        AgentSessionServer server = new AgentSessionServer(root);
        server.onStart();
        server.registerAgent(AGENT_ID, "Worker 1");
        SessionDescriptor reported = new SessionDescriptor(
                new SessionId("session-1"),
                AgentMessage.SessionState.RUNNING,
                Optional.empty(),
                Optional.empty(),
                "running");
        TestConnection connection = new TestConnection();

        try (AgentdLaunchAttempt attempt = server.provisioningControl(
                AGENT_ID,
                URI.create("https://orion.example/agent/control"),
                "/var/lib/orion/agent",
                1024,
                "2.4.1").nextAttempt()) {
            AgentControlHandler.Session session = server.open(connection);
            session.onMessage(hello(attempt));
            session.onMessage(new AgentMessage.SessionList(List.of(reported)));
        }

        assertThat(connection.handshakeComplete).isTrue();
        assertThat(connection.sent)
                .hasSize(2)
                .first().isInstanceOf(AgentMessage.Welcome.class);
        assertThat(connection.sent.get(1)).isEqualTo(new AgentMessage.RequestSessionList());

        server.onStop();
        assertThat(connection.closed).isTrue();
        try (FileSystemAgentRegistry ignored = new FileSystemAgentRegistry(root.resolve("agents"));
             FileSystemSessionRegistry sessions = new FileSystemSessionRegistry(root.resolve("sessions"))) {
            assertThat(sessions.ownedBy(AGENT_ID))
                    .singleElement()
                    .extracting(record -> record.reported())
                    .isEqualTo(reported);
        }
    }

    @Test
    void rejectsConnectionsOutsideItsRunningLifecycle() {
        AgentSessionServer server = new AgentSessionServer(root);
        TestConnection connection = new TestConnection();

        server.open(connection);

        assertThat(connection.closed).isTrue();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void failedStartupReleasesAlreadyOpenedRegistry() throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("sessions"), "not a directory");
        AgentSessionServer server = new AgentSessionServer(root);

        assertThatThrownBy(server::onStart).isInstanceOf(Exception.class);
        assertThat(server.isRunning()).isFalse();
        try (FileSystemAgentRegistry ignored = new FileSystemAgentRegistry(root.resolve("agents"))) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void shutdownClosesEveryConnectionAndRegistryWhenTransportCleanupFails() throws Exception {
        AgentId secondAgent = new AgentId("agent-2");
        AgentSessionServer server = new AgentSessionServer(root);
        server.onStart();
        server.registerAgent(AGENT_ID, "Worker 1");
        server.registerAgent(secondAgent, "Worker 2");
        TestConnection first = authenticate(server, AGENT_ID, new IllegalStateException("first close failed"));
        TestConnection second = authenticate(
                server, secondAgent, new IllegalStateException("second close failed"));

        assertThatThrownBy(server::onStop)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("close failed");

        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
        try (FileSystemAgentRegistry ignored = new FileSystemAgentRegistry(root.resolve("agents"));
             FileSystemSessionRegistry ignoredSessions =
                     new FileSystemSessionRegistry(root.resolve("sessions"))) {
            assertThat(ignored).isNotNull();
            assertThat(ignoredSessions).isNotNull();
        }
    }

    private static TestConnection authenticate(
            AgentSessionServer server, AgentId agentId, RuntimeException closeFailure) throws Exception {
        TestConnection connection = new TestConnection(closeFailure);
        try (AgentdLaunchAttempt attempt = server.provisioningControl(
                agentId,
                URI.create("https://orion.example/agent/control"),
                "/var/lib/orion/agent",
                1024,
                "2.4.1").nextAttempt()) {
            server.open(connection).onMessage(hello(agentId, attempt));
        }
        return connection;
    }

    private static AgentMessage.Hello hello(AgentdLaunchAttempt attempt) {
        return hello(AGENT_ID, attempt);
    }

    private static AgentMessage.Hello hello(AgentId agentId, AgentdLaunchAttempt attempt) {
        byte[] permit = Base64.getUrlDecoder().decode(attempt.permit().copyBytes());
        return new AgentMessage.Hello(
                AgentProtocolVersion.CURRENT,
                JournalFormatVersion.CURRENT,
                agentId,
                new AgentInstanceId(UUID.randomUUID()),
                "2.4.1",
                new MachineInfo("worker-1", "linux", "aarch64"),
                Map.of("pty", "true"),
                Optional.of(new AgentAuthentication(
                        attempt.request().generation(),
                        attempt.request().launchId(),
                        AgentAuthentication.Kind.LAUNCH_PERMIT,
                        ProtocolBytes.copyOf(permit))));
    }

    private static final class TestConnection implements AgentControlHandler.Connection {
        private final List<AgentMessage> sent = new ArrayList<>();
        private final RuntimeException closeFailure;
        private boolean handshakeComplete;
        private boolean closed;

        private TestConnection() {
            this(null);
        }

        private TestConnection(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            sent.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
