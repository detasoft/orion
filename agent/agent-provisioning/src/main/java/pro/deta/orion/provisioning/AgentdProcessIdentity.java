package pro.deta.orion.provisioning;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.nio.file.Path;

public record AgentdProcessIdentity(
        long pid,
        long startEpochMillis,
        String nativeStartToken,
        String releaseDirectory,
        String executable,
        AgentLaunchId launchId,
        AgentGeneration generation
) {
    private static final int MAX_TOKEN_LENGTH = 512;

    public AgentdProcessIdentity {
        if (pid <= 0 || startEpochMillis <= 0) {
            throw new IllegalArgumentException("AgentD process times and identifiers must be positive");
        }
        nativeStartToken = requireAscii(nativeStartToken, "native process start token", MAX_TOKEN_LENGTH);
        releaseDirectory = requireNormalizedAbsolute(releaseDirectory, "release directory");
        executable = requireNormalizedAbsolute(executable, "executable");
        if (!Path.of(executable).startsWith(Path.of(releaseDirectory))) {
            throw new IllegalArgumentException("AgentD executable must be inside its release directory");
        }
        if (launchId == null || generation == null) {
            throw new IllegalArgumentException("AgentD launch identity must not be null");
        }
    }

    private static String requireAscii(String value, String label, int maximumLength) {
        if (value == null || value.isEmpty() || value.length() > maximumLength) {
            throw new IllegalArgumentException("AgentD " + label + " is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("AgentD " + label + " is invalid");
            }
        }
        return value;
    }

    private static String requireNormalizedAbsolute(String value, String label) {
        if (value == null || value.isBlank() || containsControl(value)) {
            throw new IllegalArgumentException("AgentD " + label + " is invalid");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().toString().equals(value)) {
            throw new IllegalArgumentException(
                    "AgentD " + label + " must be an exact normalized absolute path");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
