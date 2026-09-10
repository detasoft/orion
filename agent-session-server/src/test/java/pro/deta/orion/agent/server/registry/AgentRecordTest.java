package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.MachineInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRecordTest {
    @Test
    void completeRecordRoundTripsThroughVersionedEncoding() throws IOException {
        AgentRecord record = completeRecord();

        byte[] encoded = new AgentRecordCodec().encode(record);

        assertThat(new AgentRecordCodec().decode(encoded)).isEqualTo(record);
    }

    @Test
    void codecRejectsNonCanonicalTimestampNanoseconds() throws IOException {
        byte[] encoded = new AgentRecordCodec().encode(completeRecord());
        ByteBuffer.wrap(encoded, encoded.length - Integer.BYTES, Integer.BYTES)
                .putInt(1_000_000_000);

        assertThatThrownBy(() -> new AgentRecordCodec().decode(encoded))
                .isInstanceOf(IOException.class);
    }

    @Test
    void recordOwnsCredentialAndCapabilityInputs() {
        byte[] digestBytes = digestBytes();
        Map<String, String> capabilities = new LinkedHashMap<>(Map.of("pty", "true"));
        AgentRecord.CredentialDigest digest = new AgentRecord.CredentialDigest(digestBytes);
        AgentRecord.Observation observation = observation(capabilities);

        digestBytes[0] = 99;
        capabilities.clear();

        assertThat(digest.bytes()).containsExactly(digestBytes());
        assertThat(observation.capabilities()).containsEntry("pty", "true");

        byte[] returned = digest.bytes();
        returned[0] = 88;
        assertThat(digest.bytes()).containsExactly(digestBytes());
    }

    @Test
    void recordRejectsInvalidRegistrationAndCredentialMetadata() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AgentRecord(new AgentId("agent-1"), " ", Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRecord(
                new AgentId("agent-1"),
                "x".repeat(AgentRecord.MAX_DISPLAY_NAME_BYTES + 1),
                Optional.empty(),
                Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AgentRecord.CredentialDigest(new byte[31]));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AgentRecord.CredentialDigest(new byte[33]));
    }

    @Test
    void observationMetadataIsBoundedByUtf8BytesAndCollectionCount() {
        Map<String, String> maximumCapabilities = new LinkedHashMap<>();
        for (int index = 0; index < AgentRecord.MAX_CAPABILITIES; index++) {
            maximumCapabilities.put("key-" + index, "value");
        }
        maximumCapabilities.put("é".repeat(AgentRecord.MAX_CAPABILITY_KEY_BYTES / 2),
                "é".repeat(AgentRecord.MAX_CAPABILITY_VALUE_BYTES / 2));
        maximumCapabilities.remove("key-0");

        AgentRecord.Observation maximum = observation(
                "é".repeat(AgentRecord.MAX_AGENT_VERSION_BYTES / 2),
                new MachineInfo(
                        "é".repeat(AgentRecord.MAX_MACHINE_FIELD_BYTES / 2),
                        "é".repeat(AgentRecord.MAX_MACHINE_FIELD_BYTES / 2),
                        "é".repeat(AgentRecord.MAX_MACHINE_FIELD_BYTES / 2)),
                maximumCapabilities);

        assertThat(maximum.capabilities()).hasSize(AgentRecord.MAX_CAPABILITIES);
        assertThatIllegalArgumentException().isThrownBy(() -> observation(
                "é".repeat(AgentRecord.MAX_AGENT_VERSION_BYTES / 2 + 1),
                maximum.machine(),
                Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> observation(
                maximum.agentVersion(),
                new MachineInfo(
                        "é".repeat(AgentRecord.MAX_MACHINE_FIELD_BYTES / 2 + 1),
                        "linux",
                        "aarch64"),
                Map.of()));

        Map<String, String> tooManyCapabilities = new LinkedHashMap<>(maximumCapabilities);
        tooManyCapabilities.put("extra", "value");
        assertThatIllegalArgumentException().isThrownBy(() -> observation(
                maximum.agentVersion(), maximum.machine(), tooManyCapabilities));
        assertThatIllegalArgumentException().isThrownBy(() -> observation(
                maximum.agentVersion(), maximum.machine(), Map.of(
                        "é".repeat(AgentRecord.MAX_CAPABILITY_KEY_BYTES / 2 + 1), "value")));
        assertThatIllegalArgumentException().isThrownBy(() -> observation(
                maximum.agentVersion(), maximum.machine(), Map.of(
                        "key", "é".repeat(AgentRecord.MAX_CAPABILITY_VALUE_BYTES / 2 + 1))));
    }

    @Test
    void recordRejectsContradictoryOptionalState() {
        AgentRecord.Credential credential = credential();
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRecord.Launch(
                new AgentGeneration(1),
                new AgentLaunchId(UUID.fromString("e735b99d-9ebf-40b4-ad7f-cd12da8945c6")),
                AgentRecord.LaunchState.ONLINE,
                Optional.of(credential),
                Optional.of(credential)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRecord(
                new AgentId("agent-1"), "Build agent", Optional.empty(), Optional.of(observation(Map.of()))));
    }

    @Test
    void recordRejectsNullOptionalContainers() {
        assertThatNullPointerException().isThrownBy(
                () -> new AgentRecord(new AgentId("agent-1"), "Build agent", null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(
                () -> new AgentRecord(new AgentId("agent-1"), "Build agent", Optional.empty(), null));
    }

    private static AgentRecord completeRecord() {
        AgentRecord.Launch launch = new AgentRecord.Launch(
                new AgentGeneration(7),
                new AgentLaunchId(UUID.fromString("662d904d-6aac-4383-b9a7-eb955d18bd4b")),
                AgentRecord.LaunchState.ONLINE,
                Optional.empty(),
                Optional.of(credential()));
        return new AgentRecord(
                new AgentId("agent-1"),
                "Build agent",
                Optional.of(launch),
                Optional.of(observation(Map.of("pty", "true", "journal", "v1"))));
    }

    private static AgentRecord.Credential credential() {
        return new AgentRecord.Credential(
                new AgentRecord.CredentialDigest(digestBytes()),
                Instant.parse("2026-09-10T12:00:00Z"));
    }

    private static AgentRecord.Observation observation(Map<String, String> capabilities) {
        return observation(
                "1.2.3",
                new MachineInfo("worker-1", "linux", "aarch64"),
                capabilities);
    }

    private static AgentRecord.Observation observation(
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities) {
        return new AgentRecord.Observation(
                new AgentGeneration(7),
                new AgentLaunchId(UUID.fromString("662d904d-6aac-4383-b9a7-eb955d18bd4b")),
                new AgentInstanceId(UUID.fromString("88fd20ae-89f3-4ec8-af65-a3d66ca499b6")),
                agentVersion,
                machine,
                capabilities,
                Instant.parse("2026-09-10T11:59:00Z"));
    }

    private static byte[] digestBytes() {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }
        return bytes;
    }
}
