package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;

import java.util.Map;
import java.util.Objects;

/** Immutable launch identity and transport authority published only after WELCOME delivery. */
public final class AuthenticatedConnectionContext {
    private final AgentId agentId;
    private final AgentGeneration generation;
    private final AgentLaunchId launchId;
    private final AgentInstanceId instanceId;
    private final String agentVersion;
    private final MachineInfo machine;
    private final Map<String, String> capabilities;
    private final ConnectionId connectionId;
    private final AgentControlHandler.Connection connection;
    private final Renewal renewal;

    AuthenticatedConnectionContext(
            AgentId agentId,
            AgentGeneration generation,
            AgentLaunchId launchId,
            AgentInstanceId instanceId,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            ConnectionId connectionId,
            AgentControlHandler.Connection connection,
            Renewal renewal) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.launchId = Objects.requireNonNull(launchId, "launchId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.capabilities = Map.copyOf(capabilities);
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.renewal = Objects.requireNonNull(renewal, "renewal");
    }

    public AgentId agentId() {
        return agentId;
    }

    public AgentGeneration generation() {
        return generation;
    }

    public AgentLaunchId launchId() {
        return launchId;
    }

    public AgentInstanceId instanceId() {
        return instanceId;
    }

    public String agentVersion() {
        return agentVersion;
    }

    public MachineInfo machine() {
        return machine;
    }

    public Map<String, String> capabilities() {
        return capabilities;
    }

    public ConnectionId connectionId() {
        return connectionId;
    }

    public AgentControlHandler.Connection connection() {
        return connection;
    }

    public RenewalResult renewReconnectToken() {
        return renewal.renew();
    }

    public enum RenewalResult {
        RENEWED,
        REJECTED,
        PERSISTENCE_FAILED
    }

    @FunctionalInterface
    interface Renewal {
        RenewalResult renew();
    }
}
