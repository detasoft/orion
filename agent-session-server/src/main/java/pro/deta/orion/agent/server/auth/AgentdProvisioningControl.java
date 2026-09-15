package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLabel;
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
    private final AgentLabel agentLabel;
    private final URI serverUri;
    private final String stateDirectory;
    private final int maxFrameBytes;
    private final String agentVersion;
    private final boolean allowUnsecure;

    public AgentdProvisioningControl(
            FileSystemAgentRegistry registry,
            AgentControlAuthenticator authenticator,
            AuthenticatedAgentConnections connections,
            AgentLabel agentLabel,
            URI serverUri,
            String stateDirectory,
            int maxFrameBytes,
            String agentVersion,
            boolean allowUnsecure) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
        this.connections = java.util.Objects.requireNonNull(connections, "connections");
        AgentdLaunchRequest validated = new AgentdLaunchRequest(
                serverUri,
                stateDirectory,
                agentLabel,
                new AgentGeneration(1),
                new AgentLaunchId(new UUID(0, 0)),
                maxFrameBytes,
                agentVersion, allowUnsecure);
        this.agentLabel = validated.agentLabel();
        this.serverUri = validated.serverUri();
        this.stateDirectory = validated.stateDirectory();
        this.maxFrameBytes = validated.maxFrameBytes();
        this.agentVersion = validated.agentVersion();
        this.allowUnsecure = validated.allowUnsecure();
    }

    @Override
    public AgentdLaunchAttempt nextAttempt() throws ProvisioningException {
        AgentRecord allocated;
        try {
            allocated = registry.allocateLaunch(agentLabel, registry.find(agentLabel)
                    .flatMap(record -> record.registration().map(AgentRecord.Registration::instanceId)));
        } catch (AgentRegistryException failure) {
            throw failure("Could not durably allocate an AgentD launch", failure);
        }
        AgentRecord.Launch launch = allocated.launch().orElseThrow();
        AgentdLaunchRequest request = new AgentdLaunchRequest(
                serverUri,
                stateDirectory,
                agentLabel,
                launch.generation(),
                launch.launchId(),
                maxFrameBytes,
                agentVersion, allowUnsecure);
        AgentControlAuthenticator.PermitIssueResult result = authenticator.issueLaunchPermit(
                agentLabel, launch.generation(), launch.launchId());
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
        return connections.awaitSustainedOffline(agentLabel, timeout);
    }

    @Override
    public boolean awaitOnline(AgentLaunchId launchId, Duration timeout) throws InterruptedException {
        return connections.awaitOnline(agentLabel, launchId, timeout);
    }

    private static ProvisioningException failure(String message, AgentRegistryException cause) {
        return new ProvisioningException(ACTIVATION, message, cause);
    }
}
