package pro.deta.orion.agentd.core;

import java.time.Instant;
import java.nio.file.Path;
import java.util.Objects;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

public record AgentProcessMetadata(
        long pid,
        long startEpochMillis,
        AgentLaunchId launchId,
        AgentGeneration generation,
        String executable
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
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        Path command = Path.of(executable);
        if (!command.isAbsolute() || !command.normalize().toString().equals(executable)) {
            throw new IllegalArgumentException("executable must be an exact normalized absolute path");
        }
        for (int index = 0; index < executable.length(); index++) {
            char character = executable.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException("executable must contain printable ASCII only");
            }
        }
    }

    public static AgentProcessMetadata current(AgentLaunchContext context) {
        ProcessHandle process = ProcessHandle.current();
        long start = process.info().startInstant().orElseGet(Instant::now).toEpochMilli();
        String executable = process.info().command()
                .map(command -> Path.of(command).toAbsolutePath().normalize().toString())
                .orElseThrow(() -> new IllegalStateException("Exact AgentD executable is unavailable"));
        return new AgentProcessMetadata(
                process.pid(), start, context.launchId(), context.generation(), executable);
    }
}
