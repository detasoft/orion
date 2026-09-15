package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry.RegistrationLease;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Authenticated launch identity and revocable transport authority published after WELCOME delivery. */
public final class AuthenticatedConnectionContext {
    private final AgentLabel agentLabel;
    private final AgentGeneration generation;
    private final AgentLaunchId launchId;
    private final AgentInstanceId instanceId;
    private final String agentVersion;
    private final MachineInfo machine;
    private final Map<String, String> capabilities;
    private final ConnectionId connectionId;
    private final AgentControlHandler.Connection connection;
    private final Renewal renewal;
    private final Observation observation;
    private volatile boolean authoritative = true;
    private final Supplier<RegistrationLease> authority;

    AuthenticatedConnectionContext(
            AgentLabel agentLabel,
            AgentGeneration generation,
            AgentLaunchId launchId,
            AgentInstanceId instanceId,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            ConnectionId connectionId,
            AgentControlHandler.Connection connection,
            Renewal renewal,
            Observation observation,
            Supplier<RegistrationLease> authority) {
        this.agentLabel = Objects.requireNonNull(agentLabel, "agentLabel");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.launchId = Objects.requireNonNull(launchId, "launchId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.capabilities = Map.copyOf(capabilities);
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.renewal = Objects.requireNonNull(renewal, "renewal");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    public RegistrationLease acquireAuthority() {
        RegistrationLease lease = authority.get();
        if (!authoritative) {
            lease.close();
            throw new IllegalStateException("Agent connection has been revoked");
        }
        return lease;
    }

    public AgentLabel agentLabel() {
        return agentLabel;
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
        try (var ignored = acquireAuthority()) {
            synchronized (this) {
                return authoritative ? renewal.renew() : RenewalResult.REJECTED;
            }
        } catch (IllegalStateException failure) {
            return RenewalResult.REJECTED;
        }
    }

    synchronized void revoke() {
        authoritative = false;
    }

    ObservationResult recordObservation(
            String observedAgentVersion,
            MachineInfo observedMachine,
            Map<String, String> observedCapabilities,
            Instant observedAt) {
        return observation.record(
                observedAgentVersion, observedMachine, observedCapabilities, observedAt);
    }

    public enum RenewalResult {
        RENEWED,
        REJECTED,
        PERSISTENCE_FAILED
    }

    enum ObservationResult {
        RECORDED,
        REJECTED,
        PERSISTENCE_FAILED
    }

    @FunctionalInterface
    interface Renewal {
        RenewalResult renew();
    }

    @FunctionalInterface
    interface Observation {
        ObservationResult record(
                String agentVersion,
                MachineInfo machine,
                Map<String, String> capabilities,
                Instant observedAt);
    }
}
