package pro.deta.orion.agentd.core;

import java.util.Objects;
import java.util.UUID;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

public record AgentLaunchContext(
        AgentId agentId,
        AgentGeneration generation,
        AgentLaunchId launchId,
        AgentInstanceId instanceId,
        LaunchPermit permit
) implements AutoCloseable {
    public AgentLaunchContext {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(launchId, "launchId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(permit, "permit");
    }

    public static AgentLaunchContext create(AgentConfiguration configuration, LaunchPermit permit) {
        Objects.requireNonNull(configuration, "configuration");
        return new AgentLaunchContext(
                configuration.agentId(),
                configuration.generation(),
                configuration.launchId(),
                new AgentInstanceId(UUID.randomUUID()),
                permit);
    }

    @Override
    public void close() {
        permit.close();
    }
}
