package pro.deta.orion.agent.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.AgentRecord;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.provisioning.AgentdLaunchAttempt;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentdProvisioningControlTest {
    private static final AgentId AGENT_ID = new AgentId("agent-1");

    @TempDir
    Path root;

    @Test
    void launchAttemptPersistsItsCredentialBeforeReturningIt() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT_ID, "Worker 1");
            AuthenticatedAgentConnections connections = connections();
            AgentdProvisioningControl control = control(registry, connections);

            try (AgentdLaunchAttempt attempt = control.nextAttempt()) {
                AgentRecord persisted = registry.find(AGENT_ID).orElseThrow();
                AgentRecord.Launch launch = persisted.launch().orElseThrow();

                assertThat(launch.generation()).isEqualTo(attempt.request().generation());
                assertThat(launch.launchId()).isEqualTo(attempt.request().launchId());
                assertThat(launch.state()).isEqualTo(AgentRecord.LaunchState.STARTING);
                assertThat(launch.launchPermit()).isPresent();
                assertThat(attempt.permit().copyBytes()).isNotEmpty();
            }
        }
    }

    @Test
    void freshLaunchFencesThePreviousGenerationBeforeReturning() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT_ID, "Worker 1");
            AgentRecord.Launch previous = registry.allocateLaunch(AGENT_ID).launch().orElseThrow();
            AuthenticatedAgentConnections connections = connections();
            TestConnection oldConnection = new TestConnection();
            connections.activate(context(previous.generation(), previous.launchId(), oldConnection));
            AgentdProvisioningControl control = control(registry, connections);

            try (AgentdLaunchAttempt attempt = control.nextAttempt()) {
                assertThat(attempt.request().generation().value()).isEqualTo(2);
                assertThat(oldConnection.closed).isTrue();
                assertThat(connections.active(AGENT_ID)).isEmpty();
            }
        }
    }

    @Test
    void onlineWaitAcceptsOnlyTheRequestedLaunch() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT_ID, "Worker 1");
            AuthenticatedAgentConnections connections = connections();
            AgentdProvisioningControl control = control(registry, connections);
            AgentLaunchId expected = new AgentLaunchId(UUID.randomUUID());

            connections.activate(context(new AgentLaunchId(UUID.randomUUID())));
            assertThat(control.awaitOnline(expected, Duration.ZERO)).isFalse();

            connections.activate(context(expected));
            assertThat(control.awaitOnline(expected, Duration.ZERO)).isTrue();
        }
    }

    @Test
    void sustainedOfflineWindowAllowsRecovery() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT_ID, "Worker 1");
            AgentdProvisioningControl control = control(registry, connections());

            assertThat(control.awaitSustainedOffline(Duration.ofMillis(20))).isTrue();
        }
    }

    @Test
    void reconnectDuringOfflineWindowPreventsRecovery() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT_ID, "Worker 1");
            AuthenticatedAgentConnections connections = connections();
            AgentdProvisioningControl control = control(registry, connections);
            CountDownLatch started = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting = executor.submit(() -> {
                    started.countDown();
                    return control.awaitSustainedOffline(Duration.ofSeconds(2));
                });
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

                connections.activate(context(new AgentLaunchId(UUID.randomUUID())));

                assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
            }
        }
    }

    private AgentdProvisioningControl control(
            FileSystemAgentRegistry registry, AuthenticatedAgentConnections connections) {
        return new AgentdProvisioningControl(
                registry,
                new AgentControlAuthenticator(registry, connections::activate),
                connections,
                AGENT_ID,
                URI.create("https://orion.example/agent/control"),
                "/var/lib/orion/agent",
                1024,
                "2.4.1");
    }

    private static AuthenticatedAgentConnections connections() {
        return new AuthenticatedAgentConnections(ignored -> new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
            }

            @Override
            public void onClosed(Throwable failure) {
            }
        });
    }

    private static AuthenticatedConnectionContext context(AgentLaunchId launchId) {
        return context(new AgentGeneration(1), launchId, new TestConnection());
    }

    private static AuthenticatedConnectionContext context(
            AgentGeneration generation, AgentLaunchId launchId, TestConnection connection) {
        return new AuthenticatedConnectionContext(
                AGENT_ID,
                generation,
                launchId,
                new AgentInstanceId(UUID.randomUUID()),
                "2.4.1",
                new MachineInfo("worker-1", "linux", "aarch64"),
                Map.of(),
                new ConnectionId(UUID.randomUUID().toString()),
                connection,
                () -> AuthenticatedConnectionContext.RenewalResult.RENEWED,
                (agentVersion, machine, capabilities, observedAt) ->
                        AuthenticatedConnectionContext.ObservationResult.RECORDED);
    }

    private static final class TestConnection implements AgentControlHandler.Connection {
        private boolean closed;

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            return CompletableFuture.completedFuture(null);
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
