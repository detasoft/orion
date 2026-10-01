package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.agent.server.registry.AgentRecord;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Real durable registration and ownership shared by replication contract tests. */
public final class RegisteredAgentFixture implements AutoCloseable {
    public final FileSystemAgentRegistry agents;
    public final FileSystemSessionRegistry sessions;
    public final AgentLabel label;
    public AuthenticatedConnectionContext context;

    public RegisteredAgentFixture(Path root, AgentLabel label, SessionId... ownedSessions) throws Exception {
        agents = new FileSystemAgentRegistry(root.resolve("agents"));
        sessions = new FileSystemSessionRegistry(root.resolve("sessions"));
        this.label = label;
        agents.register(label, label.value());
        for (SessionId session : ownedSessions) {
            sessions.reserveStart(label, session);
        }
        launch();
    }

    public void launch() throws Exception {
        context = null;
        AgentControlAuthenticator authenticator = new AgentControlAuthenticator(agents, accepted -> {
            context = accepted;
            return new AgentControlHandler.Session() {
                public void onMessage(AgentMessage message) { }
                public void onClosed(Throwable failure) { }
            };
        });
        AgentRecord.Launch launch = agents.allocateLaunch(label, agents.find(label)
                    .flatMap(record -> record.registration().map(AgentRecord.Registration::instanceId))).launch().orElseThrow();
        var issued = (AgentControlAuthenticator.PermitIssueResult.Issued)
                authenticator.issueLaunchPermit(label, launch.generation(), launch.launchId());
        try (var permit = issued.permit()) {
            authenticator.open(new AgentControlHandler.Connection() {
                public CompletionStage<Void> send(AgentMessage message) {
                    return CompletableFuture.completedFuture(null);
                }
                public void handshakeComplete(AuthenticatedConnectionContext context) { }
                public void close() { }
            }).onMessage(new AgentMessage.Hello(AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                    label, new AgentInstanceId(UUID.randomUUID()), "test",
                    new MachineInfo("worker", "linux", "aarch64"), Map.of(),
                    Optional.of(new AgentAuthentication(launch.generation(), launch.launchId(),
                            AgentAuthentication.Kind.LAUNCH_PERMIT,
                            ProtocolBytes.copyOf(Base64.getUrlDecoder().decode(permit.copyBytes()))))));
        }
        if (context == null) {
            throw new IllegalStateException("Test registration failed");
        }
    }

    @Override
    public void close() throws Exception {
        sessions.close();
        agents.close();
    }
}
