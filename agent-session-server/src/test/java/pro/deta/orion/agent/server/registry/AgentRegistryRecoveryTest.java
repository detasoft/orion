package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentLabel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryRecoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void truncatedAndMalformedRecordsAreRejected() throws IOException {
        Path root = createRoot();
        AgentLabel agentLabel = new AgentLabel("agent-1");
        Path record = root.resolve(AgentRecordCodec.fileName(agentLabel));

        Files.write(record, new byte[]{1, 2, 3});
        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);

        byte[] encoded = encodedRecord(agentLabel);
        encoded[0] = 0;
        Files.write(record, encoded);
        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);
    }

    @Test
    void oversizedRecordsAreRejectedAsStoredCorruption() throws IOException {
        Path root = createRoot();
        AgentLabel agentLabel = new AgentLabel("agent-1");
        Files.write(
                root.resolve(AgentRecordCodec.fileName(agentLabel)),
                new byte[AgentRecordCodec.MAX_RECORD_BYTES + 1]);

        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);
    }

    @Test
    void unsupportedRecordVersionsAreRejected() throws IOException {
        Path root = createRoot();
        AgentLabel agentLabel = new AgentLabel("agent-1");
        byte[] encoded = encodedRecord(agentLabel);
        ByteBuffer.wrap(encoded).putInt(4, 1);
        Files.write(root.resolve(AgentRecordCodec.fileName(agentLabel)), encoded);

        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);
        assertThat(Files.readAllBytes(root.resolve(AgentRecordCodec.fileName(agentLabel)))).isEqualTo(encoded);
    }

    @Test
    void identityMustMatchCanonicalRecordFileName() throws IOException {
        Path root = createRoot();
        AgentLabel expected = new AgentLabel("agent-1");
        AgentLabel stored = new AgentLabel("agent-2");
        Files.write(root.resolve(AgentRecordCodec.fileName(expected)), encodedRecord(stored));

        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);
    }

    @Test
    void trailingBytesAreRejected() throws IOException {
        Path root = createRoot();
        AgentLabel agentLabel = new AgentLabel("agent-1");
        byte[] encoded = encodedRecord(agentLabel);
        Files.write(
                root.resolve(AgentRecordCodec.fileName(agentLabel)),
                Arrays.copyOf(encoded, encoded.length + 1));

        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);
    }

    @Test
    void incompleteTemporaryFilesAreIgnored() throws IOException, AgentRegistryException {
        Path root = createRoot();
        Files.write(root.resolve(".incomplete-agent-record.tmp"), new byte[]{1, 2, 3});

        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(new AgentLabel("agent-1"))).isEmpty();
        }
    }

    private Path createRoot() throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve("agents"));
    }

    private static byte[] encodedRecord(AgentLabel agentLabel) throws IOException {
        return new AgentRecordCodec().encode(
                new AgentRecord(agentLabel, "Build agent", Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static void assertOpenFails(Path root, AgentRegistryException.Reason reason) {
        assertThatThrownBy(() -> new FileSystemAgentRegistry(root))
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(reason);
    }
}
