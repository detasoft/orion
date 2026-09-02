package pro.deta.orion.agentd.core;

import pro.deta.orion.agentd.protocol.AgentProtocolLimits;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record AgentConfiguration(
        URI serverUri,
        Path stateDirectory,
        AgentProtocolLimits protocolLimits,
        String agentVersion
) {
    private static final Path DEFAULT_STATE_DIRECTORY = Path.of(
            System.getProperty("user.home"), ".orion", "agentd");

    public AgentConfiguration {
        serverUri = validateServerUri(serverUri);
        stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory").toAbsolutePath().normalize();
        protocolLimits = Objects.requireNonNull(protocolLimits, "protocolLimits");
        agentVersion = requireValue(agentVersion, "agentVersion");
    }

    public static AgentConfiguration parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        URI serverUri = null;
        Path stateDirectory = DEFAULT_STATE_DIRECTORY;
        int maxFrameBytes = AgentProtocolLimits.DEFAULT_MAX_FRAME_BYTES;
        String agentVersion = "development";

        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            switch (option) {
                case "--server" -> serverUri = URI.create(nextValue(arguments, ++index, option));
                case "--state-dir" -> stateDirectory = Path.of(nextValue(arguments, ++index, option));
                case "--max-frame-bytes" ->
                        maxFrameBytes = parsePositiveInt(nextValue(arguments, ++index, option));
                case "--agent-version" -> agentVersion = nextValue(arguments, ++index, option);
                default -> throw new IllegalArgumentException("Unknown AgentD option: " + option);
            }
        }

        if (serverUri == null) {
            throw new IllegalArgumentException("Missing required option: --server");
        }
        return new AgentConfiguration(
                serverUri,
                stateDirectory,
                AgentProtocolLimits.defaults().withMaxFrameBytes(maxFrameBytes),
                agentVersion);
    }

    public Path sessionsDirectory() {
        return stateDirectory.resolve("sessions");
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

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
