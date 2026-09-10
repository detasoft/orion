package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.server.registry.AgentRecord;
import pro.deta.orion.agent.server.registry.AgentRegistryException;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.provisioning.AgentdAvailability;
import pro.deta.orion.provisioning.AgentdLaunchAttempt;
import pro.deta.orion.provisioning.AgentdLaunchAttemptSource;
import pro.deta.orion.provisioning.AgentdLaunchRequest;
import pro.deta.orion.provisioning.ProvisioningException;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static pro.deta.orion.provisioning.ProvisioningFailure.ACTIVATION;

public final class AgentdProvisioningControl implements AgentdLaunchAttemptSource, AgentdAvailability {
    private final FileSystemAgentRegistry registry;
    private final AgentControlAuthenticator authenticator;
    private final AuthenticatedAgentConnections connections;
    private final AgentId agentId;
    private final URI serverUri;
    private final String stateDirectory;
    private final int maxFrameBytes;
    private final String agentVersion;

    public AgentdProvisioningControl(
            FileSystemAgentRegistry registry,
            AgentControlAuthenticator authenticator,
            AuthenticatedAgentConnections connections,
            AgentId agentId,
            URI serverUri,
            String stateDirectory,
            int maxFrameBytes,
            String agentVersion) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
        this.connections = java.util.Objects.requireNonNull(connections, "connections");
        AgentdLaunchRequest validated = new AgentdLaunchRequest(
                serverUri,
                stateDirectory,
                agentId,
                new AgentGeneration(1),
                new AgentLaunchId(new UUID(0, 0)),
                maxFrameBytes,
                agentVersion);
        this.agentId = validated.agentId();
        this.serverUri = validated.serverUri();
        this.stateDirectory = validated.stateDirectory();
        this.maxFrameBytes = validated.maxFrameBytes();
        this.agentVersion = validated.agentVersion();
    }

    @Override
    public AgentdLaunchAttempt nextAttempt() throws ProvisioningException {
        AgentRecord allocated;
        try {
            allocated = registry.allocateLaunch(agentId);
        } catch (AgentRegistryException failure) {
            throw failure("Could not durably allocate an AgentD launch", failure);
        }
        AgentRecord.Launch launch = allocated.launch().orElseThrow();
        long previousGeneration = launch.generation().value() - 1;
        if (previousGeneration > 0) {
            connections.revokeGeneration(agentId, new AgentGeneration(previousGeneration));
        }
        AgentdLaunchRequest request = new AgentdLaunchRequest(
                serverUri,
                stateDirectory,
                agentId,
                launch.generation(),
                launch.launchId(),
                maxFrameBytes,
                agentVersion);
        AgentControlAuthenticator.PermitIssueResult result = authenticator.issueLaunchPermit(
                agentId, launch.generation(), launch.launchId());
        if (result instanceof AgentControlAuthenticator.PermitIssueResult.Issued issued) {
            return new AgentdLaunchAttempt(request, issued.permit());
        }
        AgentControlAuthenticator.PermitIssueResult.Failed failed =
                (AgentControlAuthenticator.PermitIssueResult.Failed) result;
        throw new ProvisioningException(
                ACTIVATION, "Could not durably issue an AgentD launch permit: " + failed.reason());
    }

    @Override
    public boolean awaitSustainedOffline(Duration timeout) throws InterruptedException {
        return connections.awaitSustainedOffline(agentId, timeout);
    }

    @Override
    public boolean awaitOnline(AgentLaunchId launchId, Duration timeout) throws InterruptedException {
        return connections.awaitOnline(agentId, launchId, timeout);
    }

    private static ProvisioningException failure(String message, AgentRegistryException cause) {
        return new ProvisioningException(ACTIVATION, message, cause);
    }
}
