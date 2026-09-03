package pro.deta.orion.agent.server.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.encoded;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.event;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.opaqueEvent;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.writeSegment;

class JournalDurabilityTest {
    private static final SessionId SESSION = new SessionId("session-1");
    private static final SessionId OTHER_SESSION = new SessionId("session-2");

    @TempDir
    Path root;

    @Test
    void publishesFirstAndLaterAppendsOnlyAfterRequiredForces() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        try (var storage = storage(operations)) {
            JournalAppendResult first = storage.append(SESSION, List.of(event(1), event(2)));
            operations.actions.add("result");
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.actions.add("observe-last");

            assertThat(first.durableThrough()).contains(new EventId(2));
            assertThat(operations.actions).startsWith(
                    "write",
                    "write",
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName());
            assertThat(operations.actions).endsWith(
                    "publish-catalog",
                    "result",
                    "observe-last");

            operations.actions.clear();
            JournalAppendResult later = storage.append(SESSION, List.of(event(3)));
            operations.actions.add("result");

            assertThat(later.durableThrough()).contains(new EventId(3));
            assertThat(operations.actions).startsWith(
                    "write",
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName());
            assertThat(operations.actions).endsWith("publish-catalog", "result");

            operations.actions.clear();
            JournalAppendResult empty = storage.append(SESSION, List.of());

            assertThat(empty.durableThrough()).contains(new EventId(3));
            assertThat(empty.newlyStored()).isEmpty();
            assertThat(operations.actions).isEmpty();
        }
    }

    @Test
    void forcesRotatedSegmentsBeforeCreatingTheirSuccessors() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        RotationRecordingOperations operations = new RotationRecordingOperations();
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = new FileSystemSessionJournalStorage(root, config, operations)) {
            JournalAppendResult result = storage.append(SESSION, List.of(first, second, third));

            assertThat(result.durableThrough()).contains(third.eventId());
            assertThat(operations.actions).containsSubsequence(
                    "create:00000001.cbor",
                    "force-file:00000001.cbor",
                    "force-directory:session-1",
                    "create:00000002.cbor",
                    "force-file:00000002.cbor",
                    "force-directory:session-1",
                    "create:00000003.cbor",
                    "force-file:00000003.cbor",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName(),
                    "publish-catalog");
        }
    }

    @Test
    void directoryBarrierFailureLeavesOnlyARecoverableContiguousPrefix() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        RotationRecordingOperations operations = new RotationRecordingOperations();
        operations.failSessionDirectoryForceNumber = 1;
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = new FileSystemSessionJournalStorage(root, config, operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(first, second, third)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.actions).containsExactly(
                    "create:00000001.cbor",
                    "force-file:00000001.cbor",
                    "force-directory:session-1");
            assertThat(root.resolve("session-1/00000001.cbor")).exists();
            assertThat(root.resolve("session-1/00000002.cbor")).doesNotExist();
            assertThat(root.resolve("session-1/00000003.cbor")).doesNotExist();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }

        try (var recovered = new FileSystemSessionJournalStorage(root, config)) {
            assertThat(recovered.lastEventId(SESSION)).contains(first.eventId());

            JournalAppendResult retried = recovered.append(
                    SESSION, List.of(first, second, third));

            assertThat(retried.newlyStored()).containsExactly(second, third);
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        }
    }

    @Test
    void recoversTailOnlyAfterTruncateAndDurabilityBarriers() throws Exception {
        SessionEventRecord first = event(1);
        Path active = writeIncompleteActiveTail(first, event(2));
        RecordingFileOperations operations = new RecordingFileOperations();

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(first.eventId());

            assertThat(Files.readAllBytes(active)).isEqualTo(first.encodedRecord().toByteArray());
            assertThat(operations.actions).startsWith(
                    "truncate",
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName());
            assertThat(operations.actions).endsWith("publish-catalog");
        }
    }

    @Test
    void failedTailRecoveryPoisonsTheHandleAndFreshStorageRetries() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        Path active = writeIncompleteActiveTail(first, second);
        RecordingFileOperations truncateFailure = new RecordingFileOperations();
        truncateFailure.failNextTruncate = true;

        try (var storage = storage(truncateFailure)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.lastEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(truncateFailure.actions).containsExactly("truncate");
            assertPoisoned(() -> storage.readAfter(SESSION, Optional.empty()));
        }
        assertThat(Files.size(active)).isGreaterThan(first.encodedRecord().size());

        RecordingFileOperations forceFailure = new RecordingFileOperations();
        forceFailure.failNextFileForce = true;
        try (var storage = storage(forceFailure)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.lastEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(forceFailure.actions).containsExactly("truncate", "force-file");
            assertPoisoned(() -> storage.append(SESSION, List.of(second)));
        }

        RecordingFileOperations retry = new RecordingFileOperations();
        try (var storage = storage(retry)) {
            assertThat(storage.lastEventId(SESSION)).contains(first.eventId());
            assertThat(storage.append(SESSION, List.of(second)).durableThrough())
                    .contains(second.eventId());
            assertThat(Files.readAllBytes(active)).isEqualTo(encoded(first, second));
        }
    }

    @Test
    void failedRotatedAppendCanBeRecoveredAndRetriedByFreshStorage() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());
        RecordingFileOperations failure = new RecordingFileOperations();
        failure.fileForceFailureNumber = 2;

        try (var storage = new FileSystemSessionJournalStorage(root, config, failure)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(first, second, third)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }

        try (var storage = new FileSystemSessionJournalStorage(root, config)) {
            JournalAppendResult retried = storage.append(SESSION, List.of(first, second, third));

            assertThat(retried.newlyStored()).containsExactly(third);
            assertThat(retried.durableThrough()).contains(third.eventId());
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(first.encodedRecord().toByteArray());
            assertThat(Files.readAllBytes(root.resolve("session-1/00000002.cbor")))
                    .isEqualTo(second.encodedRecord().toByteArray());
            assertThat(Files.readAllBytes(root.resolve("session-1/00000003.cbor")))
                    .isEqualTo(third.encodedRecord().toByteArray());
        }
    }

    @Test
    void rejectsSegmentNumberOverflowBeforeMutation() throws Exception {
        SessionEventRecord stored = event(1);
        Path maximum = Files.createDirectories(root.resolve(SESSION.value()))
                .resolve("99999999.cbor");
        Files.write(maximum, stored.encodedRecord().toByteArray());
        RecordingFileOperations operations = new RecordingFileOperations();
        JournalStorageConfig config = segmentConfig(stored.encodedRecord().size());

        try (var storage = new FileSystemSessionJournalStorage(root, config, operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(stored.eventId());
            operations.actions.clear();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(2))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.actions).isEmpty();
            assertThat(Files.readAllBytes(maximum)).isEqualTo(stored.encodedRecord().toByteArray());
            assertThat(maximum.resolveSibling("100000000.cbor")).doesNotExist();
        }
    }

    @Test
    void completesPartialWritesBeforeForcingAndPublishing() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        operations.maximumWriteBytes = 1;
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);

        try (var storage = storage(operations)) {
            JournalAppendResult result = storage.append(SESSION, List.of(first, second));

            assertThat(result.newlyStored()).containsExactly(first, second);
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(first, second));
            assertThat(operations.actions.stream().filter("write"::equals).count())
                    .isEqualTo((long) encoded(first, second).length);
            assertThat(operations.actions.indexOf("force-file"))
                    .isGreaterThan(operations.actions.lastIndexOf("write"));
            assertThat(operations.actions.getLast()).isEqualTo("publish-catalog");
        }
    }

    @Test
    void forcesEveryCreatedDirectoryFromTheSegmentToTheExistingParent() throws Exception {
        Path nestedRoot = root.resolve("new-parent/journals");
        RecordingFileOperations operations = new RecordingFileOperations();
        try (var storage = new FileSystemSessionJournalStorage(
                nestedRoot, testConfig(), operations)) {
            JournalAppendResult result = storage.append(SESSION, List.of(event(1)));

            assertThat(result.durableThrough()).contains(new EventId(1));
            assertThat(operations.actions).startsWith(
                    "write",
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:journals",
                    "force-directory:new-parent",
                    "force-directory:" + root.getFileName());
            assertThat(operations.actions).endsWith("publish-catalog");
        }
    }

    @Test
    void acceptsUnsignedIncreasingEventIds() throws Exception {
        SessionEventRecord lower = event(Long.MAX_VALUE);
        SessionEventRecord higher = event(Long.MIN_VALUE);

        try (var storage = storage(new RecordingFileOperations())) {
            JournalAppendResult result = storage.append(SESSION, List.of(lower, higher));

            assertThat(result.durableThrough()).contains(new EventId(Long.MIN_VALUE));
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(Long.MIN_VALUE));
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(lower, higher));
        }
    }

    @Test
    void validatesTheWholeRequestBeforeWriting() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(2), event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
            assertThat(operations.actions).isEmpty();

            storage.append(SESSION, List.of(event(1)));
            operations.actions.clear();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(
                            SESSION, List.of(event(1), event(3), event(2))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
            assertThat(operations.actions).isEmpty();
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(event(1)));
        }
    }

    @Test
    void rejectsForgedOrMalformedRecordsBeforeFilesystemOperations() throws Exception {
        SessionEventRecord first = event(1);
        byte[] incomplete = Arrays.copyOf(
                first.encodedRecord().toByteArray(), first.encodedRecord().size() - 1);
        List<SessionEventRecord> invalid = List.of(
                recordWith(new EventId(2), first.eventType(), first.encodedPayload(),
                        first.encodedRecord(), first.trailingFieldCount()),
                recordWith(first.eventId(), first.eventType() + 1, first.encodedPayload(),
                        first.encodedRecord(), first.trailingFieldCount()),
                recordWith(first.eventId(), first.eventType(), ProtocolBytes.copyOf(new byte[]{0x40}),
                        first.encodedRecord(), first.trailingFieldCount()),
                recordWith(first.eventId(), first.eventType(), first.encodedPayload(),
                        first.encodedRecord(), first.trailingFieldCount() + 1),
                recordWith(first.eventId(), first.eventType(), first.encodedPayload(),
                        ProtocolBytes.copyOf(encoded(first, event(2))), first.trailingFieldCount()),
                recordWith(first.eventId(), first.eventType(), first.encodedPayload(),
                        ProtocolBytes.copyOf(incomplete), first.trailingFieldCount()),
                recordWith(first.eventId(), first.eventType(), first.encodedPayload(),
                        ProtocolBytes.copyOf(new byte[]{(byte) 0xff}), first.trailingFieldCount()));
        RecordingFileOperations operations = new RecordingFileOperations();

        try (var storage = storage(operations)) {
            for (SessionEventRecord record : invalid) {
                assertThatExceptionOfType(JournalStorageException.class)
                        .isThrownBy(() -> storage.append(SESSION, List.of(record)))
                        .extracting(JournalStorageException::reason)
                        .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
                assertThat(operations.actions).isEmpty();
            }

            assertThat(storage.append(SESSION, List.of(first)).durableThrough())
                    .contains(new EventId(1));
        }
    }

    @Test
    void rejectsProtocolOverLimitRecordBeforeFilesystemOperations() throws Exception {
        SessionEventRecord record = event(1);
        AgentProtocolLimits limits = AgentProtocolLimits.defaults()
                .withMaxMessageBytes(record.encodedRecord().size() - 1);
        RecordingFileOperations operations = new RecordingFileOperations();

        try (var storage = new FileSystemSessionJournalStorage(
                root, new JournalStorageConfig(limits), operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(record)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
            assertThat(operations.actions).isEmpty();
            assertThat(storage.lastEventId(SESSION)).isEmpty();
        }
    }

    @Test
    void rejectsBatchOverRecordCountBeforeFilesystemOperations() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        JournalStorageConfig config = new JournalStorageConfig(
                AgentProtocolLimits.defaults(), 2, 1_024);

        try (var storage = new FileSystemSessionJournalStorage(root, config, operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(
                            SESSION, List.of(event(1), event(2), event(3))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
            assertThat(operations.actions).isEmpty();

            assertThat(storage.append(SESSION, List.of(event(1))).durableThrough())
                    .contains(new EventId(1));
        }
    }

    @Test
    void rejectsBatchOverEncodedByteLimitBeforeFilesystemOperations() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        int byteLimit = first.encodedRecord().size() + second.encodedRecord().size() - 1;
        RecordingFileOperations operations = new RecordingFileOperations();
        JournalStorageConfig config = new JournalStorageConfig(
                AgentProtocolLimits.defaults(), 10, byteLimit);

        try (var storage = new FileSystemSessionJournalStorage(root, config, operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(first, second)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
            assertThat(operations.actions).isEmpty();

            assertThat(storage.append(SESSION, List.of(first)).durableThrough())
                    .contains(new EventId(1));
        }
    }

    @Test
    void requiresPositiveSegmentTarget() {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new JournalStorageConfig(
                        limits,
                        limits.maxCollectionEntries(),
                        limits.maxMessageBytes(),
                        0))
                .withMessage("targetSegmentBytes must be positive");
    }

    @Test
    void forceFailurePoisonsOnlyTheAffectedSessionWithoutPublishing() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        operations.failNextFileForce = true;
        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(operations.actions).containsExactly("write", "force-file");
            assertThat(storage.lastEventId(OTHER_SESSION)).isEmpty();

            assertPoisoned(() -> storage.firstEventId(SESSION));
            assertPoisoned(() -> storage.lastEventId(SESSION));
            assertPoisoned(() -> storage.readAfter(SESSION, Optional.empty()));
            assertPoisoned(() -> storage.append(SESSION, List.of(event(2))));

            JournalAppendResult other = storage.append(OTHER_SESSION, List.of(event(1)));
            assertThat(other.durableThrough()).contains(new EventId(1));
            assertThat(storage.lastEventId(OTHER_SESSION)).contains(new EventId(1));
        }

        try (var recovered = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertThat(recovered.firstEventId(SESSION)).contains(new EventId(1));
            assertThat(recovered.lastEventId(SESSION)).contains(new EventId(1));
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .extracting(SessionEventRecord::eventId)
                    .containsExactly(new EventId(1));
        }
    }

    @Test
    void recoveryForcesUncertainBytesBeforeExposingTheirCursor() throws Exception {
        RecordingFileOperations failedAppendOperations = new RecordingFileOperations();
        failedAppendOperations.failNextFileForce = true;
        try (var failed = storage(failedAppendOperations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> failed.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
        }

        RecordingFileOperations failedRecoveryOperations = new RecordingFileOperations();
        failedRecoveryOperations.failNextFileForce = true;
        try (var failedRecovery = storage(failedRecoveryOperations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> failedRecovery.lastEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(failedRecoveryOperations.actions).containsExactly("force-file");

            assertPoisoned(() -> failedRecovery.firstEventId(SESSION));
            assertPoisoned(() -> failedRecovery.readAfter(SESSION, Optional.empty()));
            assertPoisoned(() -> failedRecovery.append(SESSION, List.of()));
            assertThat(failedRecoveryOperations.actions).containsExactly("force-file");
        }

        RecordingFileOperations recoveredOperations = new RecordingFileOperations();
        try (var recovered = storage(recoveredOperations)) {
            assertThat(recovered.lastEventId(SESSION)).contains(new EventId(1));
            assertThat(recoveredOperations.actions).containsSubsequence(
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName(),
                    "publish-catalog");
        }
    }

    @Test
    void recoveryRepeatsUncertainNamespaceBarriersBeforeExposingTheirCursor() throws Exception {
        RecordingFileOperations failedAppendOperations = new RecordingFileOperations();
        failedAppendOperations.failDirectoryForce = root;
        try (var failed = storage(failedAppendOperations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> failed.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
        }

        RecordingFileOperations failedRecoveryOperations = new RecordingFileOperations();
        failedRecoveryOperations.failDirectoryForce = root.resolve(SESSION.value());
        try (var failedRecovery = storage(failedRecoveryOperations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> failedRecovery.append(SESSION, List.of()))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(failedRecoveryOperations.actions).containsExactly(
                    "force-file", "force-directory:session-1");
            assertPoisoned(() -> failedRecovery.lastEventId(SESSION));
        }

        RecordingFileOperations recoveredOperations = new RecordingFileOperations();
        try (var recovered = storage(recoveredOperations)) {
            JournalAppendResult result = recovered.append(SESSION, List.of());

            assertThat(result.durableThrough()).contains(new EventId(1));
            assertThat(recoveredOperations.actions).containsSubsequence(
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName(),
                    "publish-catalog");
        }
    }

    @Test
    void recoveryForcesEverySegmentBeforeAnyDirectoryBarrier() throws Exception {
        writeSegment(root, SESSION.value(), 1, event(1));
        writeSegment(root, SESSION.value(), 2, event(2));
        RecordingFileOperations operations = new RecordingFileOperations();

        try (var recovered = storage(operations)) {
            assertThat(recovered.lastEventId(SESSION)).contains(new EventId(2));

            assertThat(operations.actions).startsWith(
                    "force-file",
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName());
            assertThat(operations.actions).endsWith("publish-catalog");
        }
    }

    @Test
    void readFailurePoisonsCachedJournalState() throws Exception {
        SessionEventRecord stored = event(1);
        writeSegment(root, SESSION.value(), 1, stored);
        Path segment = root.resolve(SESSION.value()).resolve("00000001.cbor");

        try (var storage = storage(new RecordingFileOperations())) {
            assertThat(storage.firstEventId(SESSION)).contains(new EventId(1));
            Path replacement = root.resolve("replacement-after-cache.cbor");
            Files.write(replacement, stored.encodedRecord().toByteArray());
            Files.move(replacement, segment, StandardCopyOption.REPLACE_EXISTING);

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.readAfter(SESSION, Optional.empty()))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertPoisoned(() -> storage.firstEventId(SESSION));
            assertPoisoned(() -> storage.lastEventId(SESSION));
            assertPoisoned(() -> storage.readAfter(SESSION, Optional.empty()));
            assertPoisoned(() -> storage.append(SESSION, List.of(event(2))));
        }
    }

    @Test
    void directoryForceFailureDoesNotPublishTheCatalog() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        operations.failDirectoryForce = root;
        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.actions).containsExactly(
                    "write",
                    "force-file",
                    "force-directory:session-1",
                    "force-directory:" + root.getFileName());
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsActiveSegmentReplacementBeforeWriting() throws Exception {
        SessionEventRecord stored = event(1);
        Path sessionDirectory = Files.createDirectories(root.resolve(SESSION.value()));
        Path segment = sessionDirectory.resolve("00000001.cbor");
        Files.write(segment, encoded(stored));
        ReplacingFileOperations operations = new ReplacingFileOperations(
                root.resolve("displaced.cbor"), encoded(stored));

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(1));
            operations.catalogPublished = false;
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(2))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.writeCount).isZero();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
            assertThat(Files.readAllBytes(segment)).isEqualTo(encoded(stored));
        }
    }

    @Test
    void rejectsNewSegmentReplacementBeforeWriting() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = opaqueEvent(1);
        ReplacingNewFileOperations operations = new ReplacingNewFileOperations(
                root.resolve("displaced-new.cbor"), replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.writeCount).isZero();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(replacement.encodedRecord().toByteArray());
        }
    }

    @Test
    void rejectsReplacementBetweenChannelOpenAndIdentityCapture() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        OpenIdentityRaceFileOperations operations = new OpenIdentityRaceFileOperations(
                root.resolve("displaced-open.cbor"), replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.writeCount).isZero();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsSegmentReplacementAtCatalogRebuildBoundary() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingAfterBarrierOperations operations = new ReplacingAfterBarrierOperations(
                root,
                root.resolve("session-1/00000001.cbor"),
                root.resolve("displaced-rebuild.cbor"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsSessionReplacementAtCatalogRebuildBoundary() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingSessionAfterBarrierOperations operations = new ReplacingSessionAfterBarrierOperations(
                root,
                root.resolve(SESSION.value()),
                root.resolve("displaced-rebuild-session"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsSegmentReplacementDuringCatalogPublication() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingAtPublicationOperations operations = new ReplacingAtPublicationOperations(
                root.resolve("session-1/00000001.cbor"),
                root.resolve("displaced-publication.cbor"),
                replacement.encodedRecord().toByteArray(),
                false);

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsSessionReplacementDuringCatalogPublication() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingAtPublicationOperations operations = new ReplacingAtPublicationOperations(
                root.resolve(SESSION.value()),
                root.resolve("displaced-publication-session"),
                replacement.encodedRecord().toByteArray(),
                true);

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsInPlaceSegmentMutationDuringCatalogPublication() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        MutatingAtPublicationOperations operations = new MutatingAtPublicationOperations(
                root.resolve("session-1/00000001.cbor"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertThat(operations.identityWasPreserved).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsInPlaceSegmentLengthChangeDuringCatalogPublication() throws Exception {
        AppendingAtPublicationOperations operations = new AppendingAtPublicationOperations(
                root.resolve("session-1/00000001.cbor"));

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsUntouchedSegmentReplacementDuringCatalogPublication() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        writeSegment(root, SESSION.value(), 1, first);
        writeSegment(root, SESSION.value(), 2, event(2));
        ReplacingAtPublicationOperations operations = new ReplacingAtPublicationOperations(
                root.resolve("session-1/00000001.cbor"),
                root.resolve("displaced-untouched.cbor"),
                replacement.encodedRecord().toByteArray(),
                false);
        operations.disarm();

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.arm();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(3))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsUntouchedInPlaceMutationBeforeCachedAppendVerification() throws Exception {
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        writeSegment(root, SESSION.value(), 1, event(1));
        writeSegment(root, SESSION.value(), 2, event(2));
        MutatingBeforeAppendVerificationOperations operations =
                new MutatingBeforeAppendVerificationOperations(
                        root.resolve("session-1/00000001.cbor"),
                        replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.arm();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(3))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.identityWasPreserved).isTrue();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsUntouchedInPlaceMutationImmediatelyBeforeCatalogPublication() throws Exception {
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        writeSegment(root, SESSION.value(), 1, event(1));
        writeSegment(root, SESSION.value(), 2, event(2));
        MutatingBeforePublicationOperations operations = new MutatingBeforePublicationOperations(
                root.resolve("session-1/00000001.cbor"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.arm();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(3))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertThat(operations.identityWasPreserved).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsUntouchedInPlaceMutationImmediatelyAfterCatalogPublication() throws Exception {
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        writeSegment(root, SESSION.value(), 1, event(1));
        writeSegment(root, SESSION.value(), 2, event(2));
        MutatingAtPublicationOperations operations = new MutatingAtPublicationOperations(
                root.resolve("session-1/00000001.cbor"),
                replacement.encodedRecord().toByteArray());
        operations.disarm();

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.arm();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(3))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertThat(operations.identityWasPreserved).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void cachedAppendReadsOnlyTheActivePrefixAndNotClosedHistory() throws Exception {
        byte[] payload = new byte[256 * 1024];
        Arrays.fill(payload, (byte) 0x5a);
        SessionEventCodec codec = new SessionEventCodec(AgentProtocolLimits.defaults());
        SessionEventRecord historical = codec.decode(codec.encode(
                new EventId(1),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(payload))));
        SessionEventRecord active = event(2);
        writeSegment(root, SESSION.value(), 1, historical);
        writeSegment(root, SESSION.value(), 2, active);
        Path historicalPath = root.resolve(SESSION.value()).resolve("00000001.cbor");
        Path activePath = root.resolve(SESSION.value()).resolve("00000002.cbor");
        CountingReadOperations operations = new CountingReadOperations();

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.clearReadCounts();

            SessionEventRecord appended = event(3);
            assertThat(storage.append(SESSION, List.of(appended)).durableThrough())
                    .contains(new EventId(3));

            assertThat(operations.bytesRead(historicalPath)).isZero();
            assertThat(operations.bytesRead(activePath))
                    .isEqualTo(active.encodedRecord().size() + appended.encodedRecord().size());
        }
    }

    @Test
    void cachedRotationDoesNotReadTheClosedSegmentPayload() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        Path firstPath = root.resolve("session-1/00000001.cbor");
        Path secondPath = root.resolve("session-1/00000002.cbor");
        CountingReadOperations operations = new CountingReadOperations();
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = new FileSystemSessionJournalStorage(root, config, operations)) {
            storage.append(SESSION, List.of(first));
            operations.clearReadCounts();

            storage.append(SESSION, List.of(second));

            assertThat(operations.bytesRead(firstPath)).isZero();
            assertThat(operations.bytesRead(secondPath)).isEqualTo(second.encodedRecord().size());
        }
    }

    @Test
    void rejectsActivePrefixMutationAfterForceBeforeContentVerification() throws Exception {
        SessionEventRecord stored = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        writeSegment(root, SESSION.value(), 1, stored);
        PostForcePrefixMutationOperations operations = new PostForcePrefixMutationOperations(
                root.resolve("session-1/00000001.cbor"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(1));
            operations.arm();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(2))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.mutationRan).isTrue();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsConcurrentSegmentInsertionBeforeCatalogPublication() throws Exception {
        writeSegment(root, SESSION.value(), 1, event(1));
        writeSegment(root, SESSION.value(), 2, event(2));
        InsertingAfterSessionBarrierOperations operations =
                new InsertingAfterSessionBarrierOperations(
                        root.resolve("session-1"),
                        root.resolve("session-1/00000003.cbor"),
                        event(4).encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            operations.arm();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(3))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsDuplicateSegmentRepresentationInsertedAfterCatalogPublication() throws Exception {
        writeSegment(root, SESSION.value(), 1, event(1));
        InsertingAtPublicationOperations operations = new InsertingAtPublicationOperations(
                root.resolve("session-1/00000001.cbor.zst"), new byte[]{0x01});

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(1));
            operations.arm();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(2))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsUnknownEntryInsertedAfterCatalogPublication() throws Exception {
        writeSegment(root, SESSION.value(), 1, event(1));
        InsertingAtPublicationOperations operations = new InsertingAtPublicationOperations(
                root.resolve("session-1/intruder"), new byte[]{0x01});

        try (var storage = storage(operations)) {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(1));
            operations.arm();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(2))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.publicationRan).isTrue();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void readFailurePreventsConcurrentAppendFromReturningSuccess() throws Exception {
        byte[] payload = new byte[256 * 1024];
        Arrays.fill(payload, (byte) 0x5a);
        SessionEventCodec codec = new SessionEventCodec(AgentProtocolLimits.defaults());
        SessionEventRecord stored = codec.decode(codec.encode(
                new EventId(1),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(payload))));
        writeSegment(root, SESSION.value(), 1, stored);
        Path segment = root.resolve("session-1/00000001.cbor");
        ReadAppendRaceOperations operations = new ReadAppendRaceOperations(segment, true);
        FileSystemSessionJournalStorage storage = storage(operations);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(1));
            operations.arm();
            Future<JournalStorageException> readFailure = executor.submit(
                    () -> readFailure(storage));
            assertThat(operations.readBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<JournalStorageException> appendFailure = executor.submit(
                    () -> appendFailure(storage, event(2)));
            assertThat(operations.appendAtPublication.await(5, TimeUnit.SECONDS)).isTrue();

            FileTime generation = Files.getLastModifiedTime(
                    segment, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(
                    segment, java.nio.file.StandardOpenOption.WRITE)) {
                channel.position(stored.encodedRecord().size() - 1L);
                channel.write(ByteBuffer.wrap(new byte[]{0x5b}));
            }
            Files.setLastModifiedTime(segment, generation);
            operations.allowRead.countDown();
            assertThat(operations.readFailurePublished.await(5, TimeUnit.SECONDS)).isTrue();
            operations.allowPublication.countDown();

            JournalStorageException readError = readFailure.get(5, TimeUnit.SECONDS);
            JournalStorageException appendError = appendFailure.get(5, TimeUnit.SECONDS);
            assertThat(readError).isNotNull();
            assertThat(readError.reason())
                    .isEqualTo(JournalStorageException.Reason.STORED_CORRUPTION);
            assertThat(appendError).isNotNull();
            assertThat(appendError.reason())
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertPoisoned(() -> storage.lastEventId(SESSION));
        } finally {
            operations.releaseAll();
            storage.close();
            executor.shutdownNow();
        }
    }

    @Test
    void snapshotReadAcceptsConcurrentGrowthAfterItsCommittedPrefix() throws Exception {
        SessionEventRecord stored = event(1);
        writeSegment(root, SESSION.value(), 1, stored);
        Path segment = root.resolve("session-1/00000001.cbor");
        ReadAppendRaceOperations operations = new ReadAppendRaceOperations(segment, false);
        FileSystemSessionJournalStorage storage = storage(operations);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(1));
            operations.arm();
            Future<JournalReadResult> read = executor.submit(
                    () -> storage.readAfter(SESSION, Optional.empty()));
            assertThat(operations.readBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(storage.append(SESSION, List.of(event(2))).durableThrough())
                    .contains(new EventId(2));
            operations.allowRead.countDown();

            assertThat(read.get(5, TimeUnit.SECONDS).records())
                    .extracting(SessionEventRecord::eventId)
                    .containsExactly(new EventId(1));
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
        } finally {
            operations.releaseAll();
            storage.close();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsSessionReplacementAfterDirectoryCreation() throws Exception {
        ReplacingCreatedDirectoryOperations operations = new ReplacingCreatedDirectoryOperations(
                root.resolve(SESSION.value()), root.resolve("displaced-created-session"));

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.writeCount).isZero();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsDirectoryReplacementBetweenIdentityCaptureAndChainConstruction() throws Exception {
        ReplacingCapturedDirectoryOperations operations = new ReplacingCapturedDirectoryOperations(
                root.resolve(SESSION.value()), root.resolve("displaced-captured-session"));

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.writeCount).isZero();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void reportsLeafSymlinkReplacementAsCheckedIoFailure() throws Exception {
        SymlinkingCapturedDirectoryOperations operations =
                new SymlinkingCapturedDirectoryOperations(
                        root.resolve(SESSION.value()), root.resolve("displaced-symlink-session"));

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.writeCount).isZero();
            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void appendRecoveryFailurePoisonsTheSessionEvenIfDiskIsLaterRepaired() throws Exception {
        Path segment = root.resolve(SESSION.value()).resolve("00000001.cbor");
        Files.createDirectories(segment.getParent());
        Files.write(segment, new byte[]{(byte) 0xff});

        try (var storage = storage(new RecordingFileOperations())) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.STORED_CORRUPTION);

            Files.delete(segment);
            Files.delete(segment.getParent());
            assertPoisoned(() -> storage.append(SESSION, List.of(event(1))));
            assertPoisoned(() -> storage.firstEventId(SESSION));
        }
    }

    @Test
    void closeWaitsForAcquiredAppendAndPreventsLaterIo() throws Exception {
        BlockingForceOperations operations = new BlockingForceOperations();
        FileSystemSessionJournalStorage storage = storage(operations);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        try {
            Future<JournalAppendResult> append = executor.submit(
                    () -> storage.append(SESSION, List.of(event(1))));
            assertThat(operations.forceStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> close = executor.submit(() -> {
                closeStarted.countDown();
                storage.close();
                closeFinished.countDown();
            });
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closeFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            operations.allowForce.countDown();
            assertThat(append.get(5, TimeUnit.SECONDS).durableThrough()).contains(new EventId(1));
            close.get(5, TimeUnit.SECONDS);
            int writesAfterClose = operations.writeCount;

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(OTHER_SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.CLOSED);
            assertThat(operations.writeCount).isEqualTo(writesAfterClose);
        } finally {
            operations.allowForce.countDown();
            storage.close();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsSessionReplacementDuringDirectoryForce() throws Exception {
        SessionEventRecord appended = event(1);
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingForcedDirectoryOperations operations = new ReplacingForcedDirectoryOperations(
                root.resolve(SESSION.value()),
                root.resolve("displaced-forced-session"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(appended)))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsSessionReplacementWhileDirectoryChannelOpens() throws Exception {
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingDirectoryOpenOperations operations = new ReplacingDirectoryOpenOperations(
                root.resolve(SESSION.value()),
                root.resolve("displaced-open-session"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void rejectsSessionReplacementAfterDirectoryForce() throws Exception {
        SessionEventRecord replacement = sameSizeDifferentEvent(1);
        ReplacingDirectoryAfterForceOperations operations = new ReplacingDirectoryAfterForceOperations(
                root.resolve(SESSION.value()),
                root.resolve("displaced-after-force-session"),
                replacement.encodedRecord().toByteArray());

        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(operations.catalogPublished).isFalse();
            assertPoisoned(() -> storage.lastEventId(SESSION));
        }
    }

    @Test
    void laterSessionCompletesAncestorBarriersLeftByFailedAppend() throws Exception {
        Path nestedRoot = root.resolve("new-parent/journals");
        Path failedBarrier = nestedRoot.getParent();
        AncestorBarrierOperations operations = new AncestorBarrierOperations(failedBarrier);
        try (var storage = new FileSystemSessionJournalStorage(
                nestedRoot, testConfig(), operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            operations.actions.clear();
            storage.append(OTHER_SESSION, List.of(event(1)));

            assertThat(operations.actions).containsSubsequence(
                    "force-directory:session-2",
                    "force-directory:journals",
                    "force-directory:new-parent",
                    "force-directory:" + root.getFileName(),
                    "publish-catalog");
        }
    }

    @Test
    void freshStorageCompletesAncestorBarriersLeftByFailedAppend() throws Exception {
        Path nestedRoot = root.resolve("new-parent/journals");
        Path failedBarrier = nestedRoot.getParent();
        try (var failed = new FileSystemSessionJournalStorage(
                nestedRoot, testConfig(), new AncestorBarrierOperations(failedBarrier))) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> failed.append(SESSION, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
        }

        RecordingFileOperations recoveredOperations = new RecordingFileOperations();
        try (var recovered = new FileSystemSessionJournalStorage(
                nestedRoot, testConfig(), recoveredOperations)) {
            recovered.append(OTHER_SESSION, List.of(event(1)));

            assertThat(recoveredOperations.actions).containsSubsequence(
                    "force-directory:session-2",
                    "force-directory:journals",
                    "force-directory:new-parent",
                    "force-directory:" + root.getFileName(),
                    "publish-catalog");
        }
    }

    @Test
    void appendsToAnEmptyActiveSegmentRecoveredFromDisk() throws Exception {
        Path sessionDirectory = Files.createDirectories(root.resolve(SESSION.value()));
        Path segment = Files.createFile(sessionDirectory.resolve("00000001.cbor"));

        try (var storage = storage(new RecordingFileOperations())) {
            JournalAppendResult result = storage.append(SESSION, List.of(event(1)));

            assertThat(result.durableThrough()).contains(new EventId(1));
            assertThat(storage.firstEventId(SESSION)).contains(new EventId(1));
            assertThat(Files.readAllBytes(segment)).isEqualTo(encoded(event(1)));
        }
    }

    private FileSystemSessionJournalStorage storage(DurableFileOperations operations) {
        return new FileSystemSessionJournalStorage(root, testConfig(), operations);
    }

    private static JournalStorageConfig testConfig() {
        return new JournalStorageConfig(AgentProtocolLimits.defaults());
    }

    private static JournalStorageConfig segmentConfig(long targetSegmentBytes) {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults();
        return new JournalStorageConfig(
                limits,
                limits.maxCollectionEntries(),
                limits.maxMessageBytes(),
                targetSegmentBytes);
    }

    private Path writeIncompleteActiveTail(
            SessionEventRecord complete,
            SessionEventRecord incomplete) throws IOException {
        Path sessionDirectory = Files.createDirectories(root.resolve(SESSION.value()));
        Path active = sessionDirectory.resolve("00000001.cbor");
        byte[] incompleteBytes = Arrays.copyOf(
                incomplete.encodedRecord().toByteArray(), incomplete.encodedRecord().size() - 1);
        ByteBuffer contents = ByteBuffer.allocate(complete.encodedRecord().size() + incompleteBytes.length);
        contents.put(complete.encodedRecord().toByteArray());
        contents.put(incompleteBytes);
        Files.write(active, contents.array());
        return active;
    }

    private static SessionEventRecord sameSizeDifferentEvent(long id) throws Exception {
        SessionEventRecord original = event(id);
        byte[] bytes = original.encodedRecord().toByteArray();
        bytes[bytes.length - 1] ^= 0x7f;
        SessionEventRecord replacement = new SessionEventCodec(AgentProtocolLimits.defaults()).decode(bytes);
        assertThat(replacement.eventId()).isEqualTo(original.eventId());
        assertThat(replacement.encodedRecord().size()).isEqualTo(original.encodedRecord().size());
        assertThat(replacement.encodedRecord().toByteArray())
                .isNotEqualTo(original.encodedRecord().toByteArray());
        return replacement;
    }

    private static SessionEventRecord recordWith(
            EventId eventId,
            int eventType,
            ProtocolBytes encodedPayload,
            ProtocolBytes encodedRecord,
            int trailingFieldCount) {
        return new SessionEventRecord(
                eventId, eventType, encodedPayload, encodedRecord, trailingFieldCount);
    }

    private static void overwriteAndAdvanceGeneration(Path target, byte[] bytes) throws IOException {
        FileTime previous = Files.getLastModifiedTime(target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Files.write(target, bytes);
        long advancedMillis = Math.max(System.currentTimeMillis() + 2_000, previous.toMillis() + 2_000);
        Files.setLastModifiedTime(target, FileTime.fromMillis(advancedMillis));
    }

    private static void assertPoisoned(ThrowingOperation operation) {
        assertThatExceptionOfType(JournalStorageException.class)
                .isThrownBy(operation::run)
                .extracting(JournalStorageException::reason)
                .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
    }

    private static JournalStorageException readFailure(FileSystemSessionJournalStorage storage) {
        try {
            storage.readAfter(SESSION, Optional.empty());
            return null;
        } catch (JournalStorageException e) {
            return e;
        }
    }

    private static JournalStorageException appendFailure(
            FileSystemSessionJournalStorage storage,
            SessionEventRecord record) {
        try {
            storage.append(SESSION, List.of(record));
            return null;
        } catch (JournalStorageException e) {
            return e;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static class RecordingFileOperations extends DurableFileOperations {
        private final List<String> actions = new ArrayList<>();
        private int maximumWriteBytes = Integer.MAX_VALUE;
        private boolean failNextFileForce;
        private boolean failNextTruncate;
        private int fileForceFailureNumber = -1;
        private int fileForceCount;
        private Path failDirectoryForce;

        List<String> recordedActions() {
            return actions;
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            actions.add("write");
            int originalLimit = source.limit();
            source.limit(Math.min(source.limit(), source.position() + maximumWriteBytes));
            try {
                return super.write(channel, source);
            } finally {
                source.limit(originalLimit);
            }
        }

        @Override
        void forceFile(FileChannel channel) throws IOException {
            actions.add("force-file");
            fileForceCount++;
            if (failNextFileForce || fileForceCount == fileForceFailureNumber) {
                failNextFileForce = false;
                throw new IOException("simulated file force failure");
            }
            super.forceFile(channel);
        }

        @Override
        void truncateChannel(FileChannel channel, long size) throws IOException {
            actions.add("truncate");
            if (failNextTruncate) {
                failNextTruncate = false;
                throw new IOException("simulated truncate failure");
            }
            super.truncateChannel(channel, size);
        }

        @Override
        void forceDirectoryChannel(DurableDirectory directory, FileChannel channel) throws IOException {
            actions.add("force-directory:" + directory.path().getFileName());
            if (directory.path().equals(failDirectoryForce)) {
                throw new IOException("simulated directory force failure");
            }
            super.forceDirectoryChannel(directory, channel);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            publication.run();
            actions.add("publish-catalog");
        }
    }

    private static final class RotationRecordingOperations extends DurableFileOperations {
        private final List<String> actions = new ArrayList<>();
        private String activeSegment;
        private int failSessionDirectoryForceNumber = -1;
        private int sessionDirectoryForceCount;

        @Override
        void beforeAppendOpen(Path path) {
            activeSegment = path.getFileName().toString();
            actions.add("create:" + activeSegment);
        }

        @Override
        void forceFile(FileChannel channel) throws IOException {
            actions.add("force-file:" + activeSegment);
            super.forceFile(channel);
        }

        @Override
        void forceDirectoryChannel(DurableDirectory directory, FileChannel channel)
                throws IOException {
            actions.add("force-directory:" + directory.path().getFileName());
            if (directory.path().getFileName().toString().equals(SESSION.value())) {
                sessionDirectoryForceCount++;
                if (sessionDirectoryForceCount == failSessionDirectoryForceNumber) {
                    throw new IOException("simulated segment entry directory force failure");
                }
            }
            super.forceDirectoryChannel(directory, channel);
        }

        @Override
        void publishCatalog(Runnable publication) {
            publication.run();
            actions.add("publish-catalog");
        }
    }

    private static final class ReplacingFileOperations extends DurableFileOperations {
        private final Path displaced;
        private final byte[] replacementBytes;
        private int writeCount;
        private boolean catalogPublished;

        private ReplacingFileOperations(Path displaced, byte[] replacementBytes) {
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void beforeAppendOpen(Path path) throws IOException {
            Files.move(path, displaced, StandardCopyOption.REPLACE_EXISTING);
            Files.write(path, replacementBytes);
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingNewFileOperations extends DurableFileOperations {
        private final Path displaced;
        private final byte[] replacementBytes;
        private int writeCount;
        private boolean catalogPublished;

        private ReplacingNewFileOperations(Path displaced, byte[] replacementBytes) {
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void afterAppendOpen(Path path, FileChannel channel) throws IOException {
            Files.move(path, displaced, StandardCopyOption.REPLACE_EXISTING);
            Files.write(path, replacementBytes);
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class OpenIdentityRaceFileOperations extends DurableFileOperations {
        private final Path displaced;
        private final byte[] replacementBytes;
        private int writeCount;
        private boolean catalogPublished;

        private OpenIdentityRaceFileOperations(Path displaced, byte[] replacementBytes) {
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        FileChannel openAppendChannel(Path path) throws IOException {
            FileChannel channel = super.openAppendChannel(path);
            Files.move(path, displaced, StandardCopyOption.REPLACE_EXISTING);
            Files.write(path, replacementBytes);
            return channel;
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingAfterBarrierOperations extends DurableFileOperations {
        private final Path replaceAfter;
        private final Path segment;
        private final Path displaced;
        private final byte[] replacementBytes;
        private boolean replaced;
        private boolean catalogPublished;

        private ReplacingAfterBarrierOperations(
                Path replaceAfter,
                Path segment,
                Path displaced,
                byte[] replacementBytes) {
            this.replaceAfter = replaceAfter;
            this.segment = segment;
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void afterDirectoryForce(DurableDirectory directory) throws IOException {
            if (!replaced && directory.path().equals(replaceAfter)) {
                Files.move(segment, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.write(segment, replacementBytes);
                replaced = true;
            }
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingSessionAfterBarrierOperations extends DurableFileOperations {
        private final Path replaceAfter;
        private final Path sessionDirectory;
        private final Path displaced;
        private final byte[] replacementBytes;
        private boolean replaced;
        private boolean catalogPublished;

        private ReplacingSessionAfterBarrierOperations(
                Path replaceAfter,
                Path sessionDirectory,
                Path displaced,
                byte[] replacementBytes) {
            this.replaceAfter = replaceAfter;
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void afterDirectoryForce(DurableDirectory directory) throws IOException {
            if (!replaced && directory.path().equals(replaceAfter)) {
                Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectory(sessionDirectory);
                Files.write(sessionDirectory.resolve("00000001.cbor"), replacementBytes);
                replaced = true;
            }
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingAtPublicationOperations extends DurableFileOperations {
        private final Path target;
        private final Path displaced;
        private final byte[] replacementBytes;
        private final boolean directory;
        private boolean publicationRan;
        private boolean armed = true;

        private ReplacingAtPublicationOperations(
                Path target,
                Path displaced,
                byte[] replacementBytes,
                boolean directory) {
            this.target = target;
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
            this.directory = directory;
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (!armed) {
                super.publishCatalog(publication);
                return;
            }
            publication.run();
            publicationRan = true;
            Files.move(target, displaced, StandardCopyOption.REPLACE_EXISTING);
            if (directory) {
                Files.createDirectory(target);
                Files.write(target.resolve("00000001.cbor"), replacementBytes);
            } else {
                Files.write(target, replacementBytes);
            }
        }

        private void arm() {
            armed = true;
        }

        private void disarm() {
            armed = false;
        }
    }

    private static final class MutatingAtPublicationOperations extends DurableFileOperations {
        private final Path target;
        private final byte[] replacementBytes;
        private boolean publicationRan;
        private boolean identityWasPreserved;
        private boolean armed = true;

        private MutatingAtPublicationOperations(Path target, byte[] replacementBytes) {
            this.target = target;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (!armed) {
                super.publishCatalog(publication);
                return;
            }
            publication.run();
            publicationRan = true;
            Object identityBefore = Files.readAttributes(
                    target, BasicFileAttributes.class).fileKey();
            overwriteAndAdvanceGeneration(target, replacementBytes);
            Object identityAfter = Files.readAttributes(
                    target, BasicFileAttributes.class).fileKey();
            identityWasPreserved = identityBefore != null && identityBefore.equals(identityAfter);
        }

        private void arm() {
            armed = true;
        }

        private void disarm() {
            armed = false;
        }
    }

    private static final class MutatingBeforeAppendVerificationOperations extends DurableFileOperations {
        private final Path target;
        private final byte[] replacementBytes;
        private boolean identityWasPreserved;
        private boolean catalogPublished;
        private boolean armed;

        private MutatingBeforeAppendVerificationOperations(Path target, byte[] replacementBytes) {
            this.target = target;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void afterDirectoriesCreated(DirectoryTree directories) throws IOException {
            if (!armed) {
                return;
            }
            Object identityBefore = Files.readAttributes(
                    target, BasicFileAttributes.class).fileKey();
            overwriteAndAdvanceGeneration(target, replacementBytes);
            Object identityAfter = Files.readAttributes(
                    target, BasicFileAttributes.class).fileKey();
            identityWasPreserved = identityBefore != null && identityBefore.equals(identityAfter);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (armed) {
                catalogPublished = true;
            }
            super.publishCatalog(publication);
        }

        private void arm() {
            armed = true;
        }
    }

    private static final class MutatingBeforePublicationOperations extends DurableFileOperations {
        private final Path target;
        private final byte[] replacementBytes;
        private boolean publicationRan;
        private boolean identityWasPreserved;
        private boolean armed;

        private MutatingBeforePublicationOperations(Path target, byte[] replacementBytes) {
            this.target = target;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (!armed) {
                super.publishCatalog(publication);
                return;
            }
            Object identityBefore = Files.readAttributes(
                    target, BasicFileAttributes.class).fileKey();
            overwriteAndAdvanceGeneration(target, replacementBytes);
            Object identityAfter = Files.readAttributes(
                    target, BasicFileAttributes.class).fileKey();
            identityWasPreserved = identityBefore != null && identityBefore.equals(identityAfter);
            publication.run();
            publicationRan = true;
        }

        private void arm() {
            armed = true;
        }
    }

    private static final class CountingReadOperations extends DurableFileOperations {
        private final Map<Path, Long> bytesRead = new HashMap<>();

        @Override
        void contentBytesRead(Path path, int count) {
            bytesRead.merge(path, (long) count, Long::sum);
        }

        private long bytesRead(Path path) {
            return bytesRead.getOrDefault(path, 0L);
        }

        private void clearReadCounts() {
            bytesRead.clear();
        }
    }

    private static final class PostForcePrefixMutationOperations extends DurableFileOperations {
        private final Path target;
        private final byte[] replacementPrefix;
        private boolean armed;
        private boolean mutationRan;
        private boolean catalogPublished;

        private PostForcePrefixMutationOperations(Path target, byte[] replacementPrefix) {
            this.target = target;
            this.replacementPrefix = replacementPrefix;
        }

        @Override
        void forceFile(FileChannel channel) throws IOException {
            super.forceFile(channel);
            if (armed && !mutationRan) {
                Files.write(target, replacementPrefix, java.nio.file.StandardOpenOption.WRITE);
                mutationRan = true;
            }
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (armed) {
                catalogPublished = true;
            }
            super.publishCatalog(publication);
        }

        private void arm() {
            armed = true;
        }
    }

    private static final class InsertingAfterSessionBarrierOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path inserted;
        private final byte[] bytes;
        private boolean armed;
        private boolean insertedEntry;
        private boolean catalogPublished;

        private InsertingAfterSessionBarrierOperations(
                Path sessionDirectory,
                Path inserted,
                byte[] bytes) {
            this.sessionDirectory = sessionDirectory;
            this.inserted = inserted;
            this.bytes = bytes;
        }

        @Override
        void afterDirectoryForce(DurableDirectory directory) throws IOException {
            if (armed && !insertedEntry && directory.path().equals(sessionDirectory)) {
                Files.write(inserted, bytes);
                insertedEntry = true;
            }
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (armed) {
                catalogPublished = true;
            }
            super.publishCatalog(publication);
        }

        private void arm() {
            armed = true;
        }
    }

    private static final class InsertingAtPublicationOperations extends DurableFileOperations {
        private final Path inserted;
        private final byte[] bytes;
        private boolean armed;
        private boolean publicationRan;

        private InsertingAtPublicationOperations(Path inserted, byte[] bytes) {
            this.inserted = inserted;
            this.bytes = bytes;
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (!armed) {
                super.publishCatalog(publication);
                return;
            }
            publication.run();
            publicationRan = true;
            Files.write(inserted, bytes);
        }

        private void arm() {
            armed = true;
        }
    }

    private static final class ReadAppendRaceOperations extends DurableFileOperations {
        private final Path target;
        private final boolean blockPublication;
        private final CountDownLatch readBlocked = new CountDownLatch(1);
        private final CountDownLatch allowRead = new CountDownLatch(1);
        private final CountDownLatch appendAtPublication = new CountDownLatch(1);
        private final CountDownLatch allowPublication = new CountDownLatch(1);
        private final CountDownLatch readFailurePublished = new CountDownLatch(1);
        private volatile boolean armed;
        private Thread blockedReader;

        private ReadAppendRaceOperations(Path target, boolean blockPublication) {
            this.target = target;
            this.blockPublication = blockPublication;
        }

        @Override
        void beforeContentRead(Path path) throws IOException {
            if (!armed || !path.equals(target)) {
                return;
            }
            synchronized (this) {
                if (blockedReader == null) {
                    blockedReader = Thread.currentThread();
                    readBlocked.countDown();
                }
                if (blockedReader != Thread.currentThread()) {
                    return;
                }
            }
            await(allowRead, "Timed out waiting to release the snapshot read");
        }

        @Override
        void afterReadFailurePublished() {
            readFailurePublished.countDown();
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            if (!armed || !blockPublication) {
                super.publishCatalog(publication);
                return;
            }
            appendAtPublication.countDown();
            await(allowPublication, "Timed out waiting to release catalog publication");
            super.publishCatalog(publication);
        }

        private static void await(CountDownLatch latch, String message) throws IOException {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IOException(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while coordinating journal race", e);
            }
        }

        private void arm() {
            armed = true;
        }

        private void releaseAll() {
            allowRead.countDown();
            allowPublication.countDown();
        }
    }

    private static final class AppendingAtPublicationOperations extends DurableFileOperations {
        private final Path target;
        private boolean publicationRan;

        private AppendingAtPublicationOperations(Path target) {
            this.target = target;
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            publication.run();
            publicationRan = true;
            Files.write(target, new byte[]{0x01}, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    private static final class ReplacingCreatedDirectoryOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path displaced;
        private int writeCount;
        private boolean catalogPublished;

        private ReplacingCreatedDirectoryOperations(Path sessionDirectory, Path displaced) {
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
        }

        @Override
        void afterDirectoriesCreated(DirectoryTree directories) throws IOException {
            Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectory(sessionDirectory);
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingCapturedDirectoryOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path displaced;
        private int writeCount;
        private boolean catalogPublished;

        private ReplacingCapturedDirectoryOperations(Path sessionDirectory, Path displaced) {
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
        }

        @Override
        void afterDirectoryIdentityCaptured(DurableDirectory directory) throws IOException {
            if (directory.path().equals(sessionDirectory)) {
                Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectory(sessionDirectory);
            }
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class SymlinkingCapturedDirectoryOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path displaced;
        private int writeCount;
        private boolean catalogPublished;

        private SymlinkingCapturedDirectoryOperations(Path sessionDirectory, Path displaced) {
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
        }

        @Override
        void afterDirectoryIdentityCaptured(DurableDirectory directory) throws IOException {
            if (directory.path().equals(sessionDirectory)) {
                Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.createSymbolicLink(sessionDirectory, displaced);
            }
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class BlockingForceOperations extends DurableFileOperations {
        private final CountDownLatch forceStarted = new CountDownLatch(1);
        private final CountDownLatch allowForce = new CountDownLatch(1);
        private int writeCount;

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writeCount++;
            return super.write(channel, source);
        }

        @Override
        void forceFile(FileChannel channel) throws IOException {
            forceStarted.countDown();
            try {
                if (!allowForce.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release simulated file force");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to release simulated file force", e);
            }
            super.forceFile(channel);
        }
    }

    private static final class ReplacingForcedDirectoryOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path displaced;
        private final byte[] replacementBytes;
        private boolean replaced;
        private boolean catalogPublished;

        private ReplacingForcedDirectoryOperations(
                Path sessionDirectory,
                Path displaced,
                byte[] replacementBytes) {
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void beforeDirectoryOpen(DurableDirectory directory) throws IOException {
            if (!replaced && directory.path().equals(sessionDirectory)) {
                Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectory(sessionDirectory);
                Files.write(sessionDirectory.resolve("00000001.cbor"), replacementBytes);
                replaced = true;
            }
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingDirectoryOpenOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path displaced;
        private final byte[] replacementBytes;
        private boolean replaced;
        private boolean catalogPublished;

        private ReplacingDirectoryOpenOperations(
                Path sessionDirectory,
                Path displaced,
                byte[] replacementBytes) {
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        FileChannel openDirectoryChannel(Path directory) throws IOException {
            FileChannel channel = super.openDirectoryChannel(directory);
            if (!replaced && directory.equals(sessionDirectory)) {
                Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectory(sessionDirectory);
                Files.write(sessionDirectory.resolve("00000001.cbor"), replacementBytes);
                replaced = true;
            }
            return channel;
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class ReplacingDirectoryAfterForceOperations extends DurableFileOperations {
        private final Path sessionDirectory;
        private final Path displaced;
        private final byte[] replacementBytes;
        private boolean replaced;
        private boolean catalogPublished;

        private ReplacingDirectoryAfterForceOperations(
                Path sessionDirectory,
                Path displaced,
                byte[] replacementBytes) {
            this.sessionDirectory = sessionDirectory;
            this.displaced = displaced;
            this.replacementBytes = replacementBytes;
        }

        @Override
        void afterDirectoryForce(DurableDirectory directory) throws IOException {
            if (!replaced && directory.path().equals(sessionDirectory)) {
                Files.move(sessionDirectory, displaced, StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectory(sessionDirectory);
                Files.write(sessionDirectory.resolve("00000001.cbor"), replacementBytes);
                replaced = true;
            }
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            catalogPublished = true;
            super.publishCatalog(publication);
        }
    }

    private static final class AncestorBarrierOperations extends DurableFileOperations {
        private final Path failOnceAt;
        private final List<String> actions = new ArrayList<>();
        private boolean failed;

        private AncestorBarrierOperations(Path failOnceAt) {
            this.failOnceAt = failOnceAt;
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            actions.add("write");
            return super.write(channel, source);
        }

        @Override
        void forceFile(FileChannel channel) throws IOException {
            actions.add("force-file");
            super.forceFile(channel);
        }

        @Override
        void forceDirectoryChannel(DurableDirectory directory, FileChannel channel) throws IOException {
            actions.add("force-directory:" + directory.path().getFileName());
            if (!failed && directory.path().equals(failOnceAt)) {
                failed = true;
                throw new IOException("simulated unresolved ancestor barrier");
            }
            super.forceDirectoryChannel(directory, channel);
        }

        @Override
        void publishCatalog(Runnable publication) throws IOException {
            publication.run();
            actions.add("publish-catalog");
        }
    }
}
