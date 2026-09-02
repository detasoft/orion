package pro.deta.orion.agentd.core;

import java.time.Instant;
import java.util.Objects;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

public record AgentProcessMetadata(
        long pid,
        long startEpochMillis,
        AgentLaunchId launchId,
        AgentGeneration generation
) {
    public AgentProcessMetadata {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        if (startEpochMillis < 0) {
            throw new IllegalArgumentException("process start time must not be negative");
        }
        Objects.requireNonNull(launchId, "launchId");
        Objects.requireNonNull(generation, "generation");
    }

    public static AgentProcessMetadata current(AgentLaunchContext context) {
        ProcessHandle process = ProcessHandle.current();
        long start = process.info().startInstant().orElseGet(Instant::now).toEpochMilli();
        return new AgentProcessMetadata(process.pid(), start, context.launchId(), context.generation());
    }
}
