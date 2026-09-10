package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRegistryDurabilityTest {
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final SessionId FIRST = new SessionId("session-1");
    private static final SessionId SECOND = new SessionId("session-2");

    @TempDir
    Path root;

    @Test
    void partialBatchPublicationIsFencedAndCompletedOnReopen() throws Exception {
        List<SessionDescriptor> report = List.of(descriptor(FIRST, "first"), descriptor(SECOND, "second"));
        try (FileSystemSessionRegistry registry = FileSystemSessionRegistry.withOperations(
                root,
                new FailAfterFirstMove())) {
            assertThatThrownBy(() -> registry.reconcile(AGENT, report))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.INDETERMINATE);
            assertThatThrownBy(() -> registry.find(FIRST))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.INDETERMINATE);
        }

        try (FileSystemSessionRegistry recovered = new FileSystemSessionRegistry(root)) {
            assertThat(recovered.ownedBy(AGENT))
                    .extracting(SessionRecord::descriptor)
                    .containsExactlyElementsOf(report);
        }
    }

    @Test
    void malformedUtf8IsRejectedAsStoredCorruption() throws Exception {
        String marker = "detail-marker";
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root)) {
            registry.reconcile(AGENT, List.of(descriptor(FIRST, marker)));
        }
        Path record = root.resolve(SessionRecordCodec.fileName(FIRST));
        byte[] bytes = Files.readAllBytes(record);
        byte[] encodedMarker = marker.getBytes(StandardCharsets.UTF_8);
        int markerOffset = indexOf(bytes, encodedMarker);
        assertThat(markerOffset).isNotNegative();
        bytes[markerOffset] = (byte) 0x80;
        Files.write(record, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> new FileSystemSessionRegistry(root))
                .isInstanceOf(SessionRegistryException.class)
                .extracting(failure -> ((SessionRegistryException) failure).reason())
                .isEqualTo(SessionRegistryException.Reason.STORED_CORRUPTION);
    }

    @Test
    void failedCleanupAfterDurableManifestFencesLaterWrites() throws Exception {
        try (FileSystemSessionRegistry registry = FileSystemSessionRegistry.withOperations(
                root,
                new FailAfterManifestAndDuringCleanup())) {
            assertThatThrownBy(() -> registry.reconcile(AGENT, List.of(descriptor(FIRST, "older"))))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.INDETERMINATE);
            assertThatThrownBy(() -> registry.reconcile(AGENT, List.of(descriptor(FIRST, "newer"))))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.INDETERMINATE);
        }

        try (FileSystemSessionRegistry recovered = new FileSystemSessionRegistry(root)) {
            assertThat(recovered.find(FIRST).orElseThrow().descriptor().detail()).isEqualTo("older");
        }
    }

    @Test
    void failedCleanupDirectorySyncFencesLaterWrites() throws Exception {
        try (FileSystemSessionRegistry registry = FileSystemSessionRegistry.withOperations(
                root,
                new FailDuringCleanupDirectorySync())) {
            assertThatThrownBy(() -> registry.reconcile(AGENT, List.of(descriptor(FIRST, "report"))))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.INDETERMINATE);
            assertThatThrownBy(() -> registry.find(FIRST))
                    .isInstanceOf(SessionRegistryException.class)
                    .extracting(failure -> ((SessionRegistryException) failure).reason())
                    .isEqualTo(SessionRegistryException.Reason.INDETERMINATE);
        }

        try (FileSystemSessionRegistry recovered = new FileSystemSessionRegistry(root)) {
            assertThat(recovered.find(FIRST)).isEmpty();
        }
    }

    private static SessionDescriptor descriptor(SessionId sessionId, String detail) {
        return new SessionDescriptor(
                sessionId,
                AgentMessage.SessionState.RUNNING,
                Optional.of(new EventId(1)),
                Optional.of(new EventId(2)),
                detail);
    }

    private static int indexOf(byte[] bytes, byte[] sought) {
        for (int offset = 0; offset <= bytes.length - sought.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < sought.length; index++) {
                if (bytes[offset + index] != sought[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private static final class FailAfterFirstMove extends SessionRegistryFileOperations {
        @Override
        void afterRecordMove(int movedRecords) throws IOException {
            if (movedRecords == 1) {
                throw new IOException("injected failure after first record move");
            }
        }
    }

    private static final class FailAfterManifestAndDuringCleanup
            extends SessionRegistryFileOperations {
        @Override
        void beforeTransactionDirectoryForce() throws IOException {
            throw new IOException("injected failure before transaction directory force");
        }

        @Override
        void deleteUnpreparedEntry(Path entry) throws IOException {
            throw new IOException("injected transaction cleanup failure");
        }
    }

    private static final class FailDuringCleanupDirectorySync
            extends SessionRegistryFileOperations {
        @Override
        void beforeTransactionDirectoryForce() throws IOException {
            throw new IOException("injected failure before transaction directory force");
        }

        @Override
        void forceCleanupDirectory(Path directory) throws IOException {
            throw new IOException("injected cleanup directory sync failure");
        }
    }
}
