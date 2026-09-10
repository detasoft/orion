package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.MachineInfo;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AgentRecord(
        AgentId agentId,
        String displayName,
        Optional<Launch> launch,
        Optional<Observation> observation) {
    static final int MAX_DISPLAY_NAME_BYTES = 256;
    static final int MAX_AGENT_VERSION_BYTES = 256;
    static final int MAX_MACHINE_FIELD_BYTES = 256;
    static final int MAX_CAPABILITIES = 128;
    static final int MAX_CAPABILITY_KEY_BYTES = 256;
    static final int MAX_CAPABILITY_VALUE_BYTES = 4_096;

    public AgentRecord {
        Objects.requireNonNull(agentId, "agentId");
        displayName = boundedText(displayName, MAX_DISPLAY_NAME_BYTES, "displayName", false);
        launch = Objects.requireNonNull(launch, "launch");
        observation = Objects.requireNonNull(observation, "observation");
        if (observation.isPresent() && launch.isEmpty()) {
            throw new IllegalArgumentException("An agent observation requires a launch");
        }
        if (observation.isPresent()) {
            Observation observed = observation.get();
            Launch current = launch.orElseThrow();
            long observedGeneration = observed.generation().value();
            long currentGeneration = current.generation().value();
            if (observedGeneration > currentGeneration
                    || observedGeneration == currentGeneration
                    && !observed.launchId().equals(current.launchId())) {
                throw new IllegalArgumentException("An agent observation cannot follow the current launch");
            }
        }
    }

    public enum LaunchState {
        OFFLINE,
        RECOVERING,
        STARTING,
        ONLINE,
        START_FAILED,
        RECOVERY_FAILED
    }

    public record Launch(
            AgentGeneration generation,
            AgentLaunchId launchId,
            LaunchState state,
            Optional<Credential> launchPermit,
            Optional<Credential> reconnectToken) {
        public Launch {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(launchId, "launchId");
            Objects.requireNonNull(state, "state");
            launchPermit = Objects.requireNonNull(launchPermit, "launchPermit");
            reconnectToken = Objects.requireNonNull(reconnectToken, "reconnectToken");
            if (launchPermit.isPresent() && reconnectToken.isPresent()) {
                throw new IllegalArgumentException(
                        "A launch cannot retain a permit after installing a reconnect token");
            }
        }
    }

    public record Credential(CredentialDigest digest, Instant expiresAt) {
        public Credential {
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public static final class CredentialDigest {
        public static final String ALGORITHM = "SHA-256";
        public static final int SIZE_BYTES = 32;

        private final byte[] bytes;

        public CredentialDigest(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length != SIZE_BYTES) {
                throw new IllegalArgumentException("Credential digest must contain 32 bytes");
            }
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof CredentialDigest digest
                    && Arrays.equals(bytes, digest.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "CredentialDigest[algorithm=" + ALGORITHM + "]";
        }
    }

    public record Observation(
            AgentGeneration generation,
            AgentLaunchId launchId,
            AgentInstanceId instanceId,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Instant observedAt) {
        public Observation {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(launchId, "launchId");
            Objects.requireNonNull(instanceId, "instanceId");
            agentVersion = boundedText(agentVersion, MAX_AGENT_VERSION_BYTES, "agentVersion", false);
            Objects.requireNonNull(machine, "machine");
            boundedText(machine.hostname(), MAX_MACHINE_FIELD_BYTES, "machine hostname", false);
            boundedText(machine.operatingSystem(), MAX_MACHINE_FIELD_BYTES, "machine operatingSystem", false);
            boundedText(machine.architecture(), MAX_MACHINE_FIELD_BYTES, "machine architecture", false);
            Objects.requireNonNull(capabilities, "capabilities");
            if (capabilities.size() > MAX_CAPABILITIES) {
                throw new IllegalArgumentException("capabilities contains too many entries");
            }
            Map<String, String> copied = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : capabilities.entrySet()) {
                String key = boundedText(
                        entry.getKey(), MAX_CAPABILITY_KEY_BYTES, "capability key", false);
                String value = boundedText(
                        entry.getValue(), MAX_CAPABILITY_VALUE_BYTES, "capability value", true);
                copied.put(key, value);
            }
            capabilities = Map.copyOf(copied);
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    private static String boundedText(String value, int maxBytes, String name, boolean emptyAllowed) {
        Objects.requireNonNull(value, name);
        if (!emptyAllowed && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        int size;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            size = encoded.remaining();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(name + " must be valid Unicode", e);
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException(name + " exceeds " + maxBytes + " UTF-8 bytes");
        }
        return value;
    }
}
