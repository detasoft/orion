package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.MachineInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class AgentRecordCodec {
    static final int MAX_RECORD_BYTES = 1_048_576;

    private static final int MAGIC = 0x4f524147;
    private static final int VERSION = 1;

    static String fileName(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    agentId.value().getBytes(StandardCharsets.UTF_8))) + ".agent";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    byte[] encode(AgentRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeText(output, record.agentId().value());
            writeText(output, record.displayName());
            writeOptional(output, record.launch(), launch -> writeLaunch(output, launch));
            writeOptional(output, record.observation(), observation -> writeObservation(output, observation));
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_RECORD_BYTES) {
            throw new IOException("Encoded agent record exceeds the size limit");
        }
        return encoded;
    }

    AgentRecord decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_RECORD_BYTES) {
            throw corrupt("Agent record exceeds the size limit", null);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw corrupt("Agent record has an invalid header", null);
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw corrupt("Unsupported agent record version: " + version, null);
            }
            AgentId agentId = new AgentId(readText(input, 128, "agentId"));
            String displayName = readText(input, AgentRecord.MAX_DISPLAY_NAME_BYTES, "displayName");
            Optional<AgentRecord.Launch> launch = readOptional(input, () -> readLaunch(input));
            Optional<AgentRecord.Observation> observation = readOptional(
                    input, () -> readObservation(input));
            if (input.available() != 0) {
                throw corrupt("Agent record has trailing bytes", null);
            }
            return new AgentRecord(agentId, displayName, launch, observation);
        } catch (FormatException e) {
            throw e;
        } catch (EOFException e) {
            throw corrupt("Agent record is truncated", e);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw corrupt("Agent record contains invalid fields", e);
        }
    }

    private void writeLaunch(DataOutputStream output, AgentRecord.Launch launch) throws IOException {
        output.writeLong(launch.generation().value());
        writeUuid(output, launch.launchId().value());
        output.writeByte(stateCode(launch.state()));
        writeOptional(output, launch.launchPermit(), credential -> writeCredential(output, credential));
        writeOptional(output, launch.reconnectToken(), credential -> writeCredential(output, credential));
    }

    private AgentRecord.Launch readLaunch(DataInputStream input) throws IOException {
        AgentGeneration generation = new AgentGeneration(input.readLong());
        AgentLaunchId launchId = new AgentLaunchId(readUuid(input));
        AgentRecord.LaunchState state = readState(input.readUnsignedByte());
        Optional<AgentRecord.Credential> launchPermit = readOptional(
                input, () -> readCredential(input));
        Optional<AgentRecord.Credential> reconnectToken = readOptional(
                input, () -> readCredential(input));
        return new AgentRecord.Launch(generation, launchId, state, launchPermit, reconnectToken);
    }

    private void writeCredential(DataOutputStream output, AgentRecord.Credential credential)
            throws IOException {
        output.write(credential.digest().bytes());
        writeInstant(output, credential.expiresAt());
    }

    private AgentRecord.Credential readCredential(DataInputStream input) throws IOException {
        byte[] digest = input.readNBytes(AgentRecord.CredentialDigest.SIZE_BYTES);
        if (digest.length != AgentRecord.CredentialDigest.SIZE_BYTES) {
            throw new EOFException("Credential digest is truncated");
        }
        return new AgentRecord.Credential(
                new AgentRecord.CredentialDigest(digest), readInstant(input));
    }

    private void writeObservation(DataOutputStream output, AgentRecord.Observation observation)
            throws IOException {
        output.writeLong(observation.generation().value());
        writeUuid(output, observation.launchId().value());
        writeUuid(output, observation.instanceId().value());
        writeText(output, observation.agentVersion());
        writeText(output, observation.machine().hostname());
        writeText(output, observation.machine().operatingSystem());
        writeText(output, observation.machine().architecture());
        List<Map.Entry<String, String>> capabilities = new ArrayList<>(
                observation.capabilities().entrySet());
        capabilities.sort(Comparator.comparing(Map.Entry::getKey));
        output.writeInt(capabilities.size());
        for (Map.Entry<String, String> capability : capabilities) {
            writeText(output, capability.getKey());
            writeText(output, capability.getValue());
        }
        writeInstant(output, observation.observedAt());
    }

    private AgentRecord.Observation readObservation(DataInputStream input) throws IOException {
        AgentGeneration generation = new AgentGeneration(input.readLong());
        AgentLaunchId launchId = new AgentLaunchId(readUuid(input));
        AgentInstanceId instanceId = new AgentInstanceId(readUuid(input));
        String agentVersion = readText(input, AgentRecord.MAX_AGENT_VERSION_BYTES, "agentVersion");
        MachineInfo machine = new MachineInfo(
                readText(input, AgentRecord.MAX_MACHINE_FIELD_BYTES, "machine hostname"),
                readText(input, AgentRecord.MAX_MACHINE_FIELD_BYTES, "machine operatingSystem"),
                readText(input, AgentRecord.MAX_MACHINE_FIELD_BYTES, "machine architecture"));
        int capabilityCount = input.readInt();
        if (capabilityCount < 0 || capabilityCount > AgentRecord.MAX_CAPABILITIES) {
            throw corrupt("Agent record capability count is invalid", null);
        }
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (int index = 0; index < capabilityCount; index++) {
            String key = readText(input, AgentRecord.MAX_CAPABILITY_KEY_BYTES, "capability key");
            String value = readText(input, AgentRecord.MAX_CAPABILITY_VALUE_BYTES, "capability value");
            if (capabilities.put(key, value) != null) {
                throw corrupt("Agent record contains a duplicate capability", null);
            }
        }
        return new AgentRecord.Observation(
                generation, launchId, instanceId, agentVersion, machine, capabilities, readInstant(input));
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeLong(value.getEpochSecond());
        output.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        long epochSecond = input.readLong();
        int nanosecond = input.readInt();
        if (nanosecond < 0 || nanosecond > 999_999_999) {
            throw corrupt("Agent record timestamp nanoseconds are invalid", null);
        }
        return Instant.ofEpochSecond(epochSecond, nanosecond);
    }

    private static int stateCode(AgentRecord.LaunchState state) {
        return switch (state) {
            case OFFLINE -> 0;
            case RECOVERING -> 1;
            case STARTING -> 2;
            case ONLINE -> 3;
            case START_FAILED -> 4;
            case RECOVERY_FAILED -> 5;
        };
    }

    private static AgentRecord.LaunchState readState(int code) throws FormatException {
        return switch (code) {
            case 0 -> AgentRecord.LaunchState.OFFLINE;
            case 1 -> AgentRecord.LaunchState.RECOVERING;
            case 2 -> AgentRecord.LaunchState.STARTING;
            case 3 -> AgentRecord.LaunchState.ONLINE;
            case 4 -> AgentRecord.LaunchState.START_FAILED;
            case 5 -> AgentRecord.LaunchState.RECOVERY_FAILED;
            default -> throw corrupt("Agent record launch state is invalid", null);
        };
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input, int maxBytes, String name) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maxBytes || length > input.available()) {
            throw corrupt("Agent record " + name + " length is invalid", null);
        }
        byte[] encoded = input.readNBytes(length);
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw corrupt("Agent record " + name + " is not valid UTF-8", e);
        }
    }

    private static <T> void writeOptional(
            DataOutputStream output,
            Optional<T> value,
            Writer<T> writer) throws IOException {
        output.writeByte(value.isPresent() ? 1 : 0);
        if (value.isPresent()) {
            writer.write(value.get());
        }
    }

    private static <T> Optional<T> readOptional(DataInputStream input, Reader<T> reader)
            throws IOException {
        int tag = input.readUnsignedByte();
        if (tag == 0) {
            return Optional.empty();
        }
        if (tag != 1) {
            throw corrupt("Agent record optional field tag is invalid", null);
        }
        return Optional.of(reader.read());
    }

    private static FormatException corrupt(String message, Throwable cause) {
        return new FormatException(message, cause);
    }

    @FunctionalInterface
    private interface Writer<T> {
        void write(T value) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read() throws IOException;
    }

    static final class FormatException extends IOException {
        private FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
