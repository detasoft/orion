package pro.deta.orion.agentd.core;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record AgentConfiguration(
        URI serverUri,
        Path stateDirectory,
        AgentId agentId,
        AgentGeneration generation,
        AgentLaunchId launchId,
        AgentProtocolLimits protocolLimits,
        String agentVersion
) {
    public AgentConfiguration {
        serverUri = validateServerUri(serverUri);
        stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory").toAbsolutePath().normalize();
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(launchId, "launchId");
        protocolLimits = Objects.requireNonNull(protocolLimits, "protocolLimits");
        agentVersion = requireValue(agentVersion, "agentVersion");
    }

    public static AgentConfiguration parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        URI serverUri = null;
        Path stateDirectory = null;
        AgentId agentId = null;
        AgentGeneration generation = null;
        AgentLaunchId launchId = null;
        int maxFrameBytes = AgentProtocolLimits.DEFAULT_MAX_FRAME_BYTES;
        String agentVersion = null;

        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            switch (option) {
                case "--server" -> serverUri = URI.create(nextValue(arguments, ++index, option));
                case "--state-dir" -> stateDirectory = Path.of(nextValue(arguments, ++index, option));
                case "--agent-id" -> agentId = new AgentId(nextValue(arguments, ++index, option));
                case "--generation" -> generation = new AgentGeneration(
                        parsePositiveLong(nextValue(arguments, ++index, option)));
                case "--launch-id" -> launchId = new AgentLaunchId(
                        UUID.fromString(nextValue(arguments, ++index, option)));
                case "--max-frame-bytes" ->
                        maxFrameBytes = parsePositiveInt(nextValue(arguments, ++index, option));
                case "--agent-version" -> agentVersion = nextValue(arguments, ++index, option);
                default -> throw new IllegalArgumentException("Unknown AgentD option: " + option);
            }
        }

        if (serverUri == null) {
            throw new IllegalArgumentException("Missing required option: --server");
        }
        requireOption(stateDirectory, "--state-dir");
        requireOption(agentId, "--agent-id");
        requireOption(generation, "--generation");
        requireOption(launchId, "--launch-id");
        requireOption(agentVersion, "--agent-version");
        return new AgentConfiguration(
                serverUri,
                stateDirectory,
                agentId,
                generation,
                launchId,
                AgentProtocolLimits.defaults().withMaxFrameBytes(maxFrameBytes),
                agentVersion);
    }

    public Path sessionsDirectory() {
        return stateDirectory.resolve("sessions");
    }

    public Path processLockFile() {
        return stateDirectory.resolve("agentd.lock");
    }

    private static URI validateServerUri(URI serverUri) {
        Objects.requireNonNull(serverUri, "serverUri");
        if (!"https".equalsIgnoreCase(serverUri.getScheme()) || serverUri.getHost() == null) {
            throw new IllegalArgumentException("AgentD server URI must be an absolute HTTPS URI");
        }
        if (serverUri.getUserInfo() != null) {
            throw new IllegalArgumentException("AgentD server URI must not contain credentials");
        }
        return serverUri.normalize();
    }

    private static String nextValue(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for AgentD option: " + option);
        }
        return arguments[index];
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Expected a positive integer but got: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a positive integer but got: " + value, e);
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Expected a positive integer");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a positive integer", e);
        }
    }

    private static void requireOption(Object value, String option) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required option: " + option);
        }
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
