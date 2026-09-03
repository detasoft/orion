package pro.deta.orion.agent.server.journal;

import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.encoded;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.event;

class SegmentCompressionTest {
    private static final SessionId SESSION = new SessionId("session-1");
    private static final SessionEventCodec CODEC =
            new SessionEventCodec(AgentProtocolLimits.defaults());

    @TempDir
    Path root;

    @Test
    void compressesClosedSegmentsAndReadsTheirExactLogicalRecords() throws Exception {
        SessionEventRecord first = randomEvent(1, 32_000);
        SessionEventRecord future = JournalTestRecords.opaqueEvent(2);
        SessionEventRecord second = randomEvent(3, 24_000);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = directStorage(config, new DurableFileOperations())) {
            JournalAppendResult appended = storage.append(SESSION, List.of(first, future, second));
            storage.awaitMaintenanceForTest();

            assertThat(appended.durableThrough()).contains(second.eventId());
            assertThat(names(SESSION)).containsExactly("00000001.cbor.zst", "00000002.cbor");
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            first.encodedRecord().toByteArray(),
                            future.encodedRecord().toByteArray(),
                            second.encodedRecord().toByteArray());

            SegmentCatalog catalog = new SegmentReader(config.protocolLimits())
                    .rebuild(root.resolve(SESSION.value()));
            assertThat(catalog.segments()).extracting(SegmentCatalog.Segment::number)
                    .containsExactly(1L, 2L);
            assertThat(catalog.segments().getFirst().representation())
                    .isEqualTo(SegmentCatalog.Representation.COMPRESSED);
            assertThat(catalog.segments().getFirst().firstEventId()).contains(first.eventId());
            assertThat(catalog.segments().getFirst().lastEventId()).contains(first.eventId());
            assertThat(catalog.segments().getFirst().completeByteLength())
                    .isEqualTo(first.encodedRecord().size());
        }
    }

    @Test
    void reopensACompressedSegmentSmallerThanItsLogicalContent() throws Exception {
        SessionEventRecord repetitive = repetitiveEvent(1, 256_000);
        SessionEventRecord active = event(2);
        JournalStorageConfig config = segmentConfig(repetitive.encodedRecord().size());

        try (var storage = directStorage(config, new DurableFileOperations())) {
            storage.append(SESSION, List.of(repetitive, active));
            storage.awaitMaintenanceForTest();
        }

        Path compressed = root.resolve(SESSION.value()).resolve("00000001.cbor.zst");
        assertThat(Files.size(compressed)).isLessThan(repetitive.encodedRecord().size());
        try (var recovered = directStorage(config, new DurableFileOperations())) {
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            repetitive.encodedRecord().toByteArray(),
                            active.encodedRecord().toByteArray());
        }
    }

    @Test
    void rotatesBeforeAnAppendCanExceedTheRecoverableLogicalSegmentLimit() throws Exception {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxMessageBytes(64 * 1024);
        long logicalLimit = 128 * 1024;
        JournalStorageConfig config = boundedConfig(limits, logicalLimit, 1024 * 1024);
        SessionEventRecord first = repetitiveEvent(limits, 1, 60_000);
        SessionEventRecord second = repetitiveEvent(limits, 2, 60_000);
        SessionEventRecord third = repetitiveEvent(limits, 3, 60_000);

        try (var storage = directStorage(config, new DurableFileOperations())) {
            storage.append(SESSION, List.of(first, second, third));
            storage.awaitMaintenanceForTest();
        }
        SegmentCatalog catalog = new SegmentReader(config).rebuild(root.resolve(SESSION.value()));
        assertThat(catalog.segments())
                .allSatisfy(segment -> assertThat(segment.completeByteLength())
                        .isLessThanOrEqualTo(logicalLimit));
        try (var recovered = directStorage(config, new DurableFileOperations())) {
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        }
    }

    @Test
    void rejectsCompressedLogicalOutputAboveTheConfiguredSegmentLimit() throws Exception {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxMessageBytes(64 * 1024);
        long logicalLimit = 128 * 1024;
        JournalStorageConfig config = boundedConfig(limits, logicalLimit, 1024 * 1024);
        SessionEventRecord first = repetitiveEvent(limits, 1, 50_000);
        SessionEventRecord second = repetitiveEvent(limits, 2, 50_000);
        SessionEventRecord third = repetitiveEvent(limits, 3, 50_000);
        writeCompressed(1, List.of(first, second, third), null);

        assertStoredCorruption(config);
    }

    @Test
    void rejectsACompressedFrameWhoseDeclaredWindowExceedsTheConfiguredLimit() throws Exception {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxMessageBytes(64 * 1024);
        JournalStorageConfig config = boundedConfig(limits, 128 * 1024, 1024 * 1024);
        SessionEventRecord record = repetitiveEvent(limits, 1, 32_000);
        writeCompressed(1, List.of(record), 27);

        assertStoredCorruption(config);
    }

    @Test
    void readsConcatenatedFramesWithASkippableFrameBetweenThem() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        try (OutputStream output = Files.newOutputStream(directory.resolve("00000001.cbor.zst"))) {
            output.write(compressedFrame(first));
            output.write(new byte[]{0x50, 0x2a, 0x4d, 0x18, 0x03, 0, 0, 0, 7, 8, 9});
            output.write(compressedFrame(second));
        }

        try (var storage = directStorage(segmentConfig(1), new DurableFileOperations())) {
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second);
        }
    }

    @Test
    void validatesFiniteCompressionResourceBounds() {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxMessageBytes(64 * 1024);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> boundedConfig(limits, limits.maxMessageBytes() - 1L, 1024 * 1024))
                .withMessage(
                        "maxLogicalSegmentBytes must allow one maximum-size message and not exceed 1073741824");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> boundedConfig(limits, (1L << 30) + 1, 1024 * 1024))
                .withMessage(
                        "maxLogicalSegmentBytes must allow one maximum-size message and not exceed 1073741824");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> boundedConfig(limits, 128 * 1024, 512))
                .withMessage("maxZstdWindowBytes must be between 1024 and 2147483648");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> boundedConfig(limits, 128 * 1024, (1L << 31) + 1))
                .withMessage("maxZstdWindowBytes must be between 1024 and 2147483648");
    }

    @Test
    void recoversEquivalentDualRepresentationsAndRemovesTheSource() throws Exception {
        SessionEventRecord first = randomEvent(1, 4_096);
        SessionEventRecord second = event(2);
        writeDual(SESSION, 1, encoded(first));
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = directStorage(segmentConfig(1), new DurableFileOperations())) {
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            first.encodedRecord().toByteArray(),
                            second.encodedRecord().toByteArray());
            storage.awaitMaintenanceForTest();
            assertThat(names(SESSION)).containsExactly("00000001.cbor.zst", "00000002.cbor");
        }
    }

    @Test
    void retainsValidSourceAndCleansInvalidCompressedReplacement() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        JournalTestRecords.writeSegment(root, SESSION.value(), 1, first);
        Files.write(root.resolve(SESSION.value()).resolve("00000001.cbor.zst"), new byte[]{1, 2, 3});
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = directStorage(segmentConfig(1), new DurableFileOperations())) {
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second);
            storage.awaitMaintenanceForTest();
            assertThat(storage.maintenanceFailureForTest()).isEmpty();
            assertThat(names(SESSION)).containsExactly("00000001.cbor.zst", "00000002.cbor");
        }
    }

    @Test
    void invalidReplacementAwaitingCleanupDoesNotHideItsValidSourceFromAppend() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        JournalTestRecords.writeSegment(root, SESSION.value(), 1, first);
        Files.write(root.resolve(SESSION.value()).resolve("00000001.cbor.zst"), new byte[]{1, 2, 3});
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, segmentConfig(1), new DurableFileOperations(), executor)) {
            assertThat(storage.lastEventId(SESSION)).contains(second.eventId());
            executor.awaitPending(1);

            JournalAppendResult appended = storage.append(SESSION, List.of(third));

            assertThat(appended.durableThrough()).contains(third.eventId());
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isTrue();
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        }
    }

    @Test
    void rejectsDivergentValidDualRepresentations() throws Exception {
        SessionEventRecord source = event(1);
        SessionEventRecord replacement = JournalTestRecords.opaqueEvent(1);
        writeDual(SESSION, 1, encoded(source), encoded(replacement));
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, event(2));

        assertStoredCorruption();
        assertThat(names(SESSION)).contains("00000001.cbor", "00000001.cbor.zst");
    }

    @Test
    void rejectsAnInvalidSoleCompressedSegment() throws Exception {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        Files.write(directory.resolve("00000001.cbor.zst"), new byte[]{1, 2, 3});

        assertStoredCorruption();
    }

    @Test
    void ignoresAndLaterCleansAStaleExactTemporaryFile() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        JournalTestRecords.writeSegment(root, SESSION.value(), 1, first);
        Files.write(root.resolve(SESSION.value()).resolve("00000001.cbor.zst.tmp"), new byte[]{9});
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = directStorage(segmentConfig(1), new DurableFileOperations())) {
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second);
            storage.awaitMaintenanceForTest();
            assertThat(storage.maintenanceFailureForTest()).isEmpty();
            assertThat(names(SESSION)).doesNotContain("00000001.cbor.zst.tmp");
        }
    }

    @Test
    void rejectsATemporaryFileInsertedAfterRecovery() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        JournalTestRecords.writeSegment(root, SESSION.value(), 1, first);
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, segmentConfig(1), new DurableFileOperations(), executor)) {
            assertThat(storage.lastEventId(SESSION)).contains(second.eventId());
            executor.awaitPending(1);
            Path inserted = root.resolve(SESSION.value()).resolve("00000001.cbor.zst.tmp");
            byte[] insertedBytes = {4, 5, 6};
            Files.write(inserted, insertedBytes);

            assertIoFailure(() -> storage.append(SESSION, List.of(third)));
            executor.runAll();

            assertThat(Files.readAllBytes(inserted)).isEqualTo(insertedBytes);
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        }
    }

    @Test
    void rejectsAndRetainsAnApprovedTemporaryPathWhoseIdentityChanged() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        JournalTestRecords.writeSegment(root, SESSION.value(), 1, first);
        Path temporary = root.resolve(SESSION.value()).resolve("00000001.cbor.zst.tmp");
        Files.write(temporary, new byte[]{1});
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, segmentConfig(1), new DurableFileOperations(), executor)) {
            assertThat(storage.lastEventId(SESSION)).contains(second.eventId());
            executor.awaitPending(1);
            byte[] replacement = {7, 8, 9};
            replaceIdentity(temporary, replacement);

            assertIoFailure(() -> storage.append(SESSION, List.of(third)));
            executor.runAll();

            assertThat(Files.readAllBytes(temporary)).isEqualTo(replacement);
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        }
    }

    @Test
    void rejectsAndRetainsAnInvalidCompressedPathWhoseIdentityChangedBeforeCleanup()
            throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        JournalTestRecords.writeSegment(root, SESSION.value(), 1, first);
        Path compressed = root.resolve(SESSION.value()).resolve("00000001.cbor.zst");
        Files.write(compressed, new byte[]{1, 2, 3});
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, segmentConfig(1), new DurableFileOperations(), executor)) {
            assertThat(storage.lastEventId(SESSION)).contains(second.eventId());
            executor.awaitPending(1);
            byte[] replacement = {6, 5, 4};
            replaceIdentity(compressed, replacement);

            assertIoFailure(() -> storage.append(SESSION, List.of(third)));
            executor.runAll();

            assertThat(Files.readAllBytes(compressed)).isEqualTo(replacement);
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        }
    }

    @Test
    void rejectsAndRetainsARedundantSourceWhoseIdentityChangedBeforeCleanup() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        writeDual(SESSION, 1, encoded(first));
        Path source = root.resolve(SESSION.value()).resolve("00000001.cbor");
        JournalTestRecords.writeSegment(root, SESSION.value(), 2, second);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, segmentConfig(1), new DurableFileOperations(), executor)) {
            assertThat(storage.lastEventId(SESSION)).contains(second.eventId());
            executor.awaitPending(1);
            byte[] replacement = encoded(JournalTestRecords.opaqueEvent(1));
            replaceIdentity(source, replacement);

            assertIoFailure(() -> storage.append(SESSION, List.of(third)));
            executor.runAll();

            assertThat(Files.readAllBytes(source)).isEqualTo(replacement);
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        }
    }

    @Test
    void prePublicationFailuresLeaveTheSourceAuthoritativeAndDoNotFailAppend() throws Exception {
        for (CompressionFailurePoint point : List.of(
                CompressionFailurePoint.BEFORE_TEMP_FORCE,
                CompressionFailurePoint.BEFORE_PUBLICATION)) {
            Path caseRoot = root.resolve(point.name());
            FailingCompressionOperations operations = new FailingCompressionOperations(point);
            SessionEventRecord first = event(1);
            SessionEventRecord second = event(2);

            try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                    caseRoot,
                    segmentConfig(first.encodedRecord().size()),
                    operations,
                    Runnable::run)) {
                JournalAppendResult result = storage.append(SESSION, List.of(first, second));
                storage.awaitMaintenanceForTest();

                assertThat(result.durableThrough()).contains(second.eventId());
                assertThat(Files.readAllBytes(caseRoot.resolve(SESSION.value()).resolve("00000001.cbor")))
                        .isEqualTo(first.encodedRecord().toByteArray());
                assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                        .containsExactly(first, second);
                assertThat(storage.maintenanceFailureForTest()).isPresent();
            }
        }
    }

    @Test
    void postPublicationFailureAlwaysLeavesAReadableCompleteRepresentation() throws Exception {
        FailingCompressionOperations operations =
                new FailingCompressionOperations(CompressionFailurePoint.AFTER_PUBLICATION);
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = directStorage(config, operations)) {
            JournalAppendResult result = storage.append(SESSION, List.of(first, second));
            storage.awaitMaintenanceForTest();

            assertThat(result.durableThrough()).contains(second.eventId());
            assertThat(names(SESSION)).contains("00000001.cbor", "00000001.cbor.zst");
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second);
        }

        try (var recovered = directStorage(config, new DurableFileOperations())) {
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second);
            recovered.awaitMaintenanceForTest();
            assertThat(names(SESSION)).containsExactly("00000001.cbor.zst", "00000002.cbor");
        }
    }

    @Test
    void publicationRaceNeverReplacesTheCompetingTargetOrDeletesTheSource() throws Exception {
        byte[] competitor = {9, 8, 7, 6};
        RacingPublicationOperations operations = new RacingPublicationOperations(competitor);
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations)) {
            JournalAppendResult result = storage.append(SESSION, List.of(first, second));
            storage.awaitMaintenanceForTest();

            Path directory = root.resolve(SESSION.value());
            assertThat(result.durableThrough()).contains(second.eventId());
            assertThat(Files.readAllBytes(directory.resolve("00000001.cbor.zst")))
                    .isEqualTo(competitor);
            assertThat(Files.readAllBytes(directory.resolve("00000001.cbor")))
                    .isEqualTo(first.encodedRecord().toByteArray());
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        }
    }

    @Test
    void finalContentVerificationDoesNotHoldTheSessionLock() throws Exception {
        BlockingFinalVerificationOperations operations =
                new BlockingFinalVerificationOperations();
        SessionEventRecord first = randomEvent(1, 128_000);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations);
                ExecutorService appender = Executors.newSingleThreadExecutor()) {
            storage.append(SESSION, List.of(first, second));
            assertThat(operations.verificationStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<JournalAppendResult> append =
                    appender.submit(() -> storage.append(SESSION, List.of(third)));
            try {
                assertThat(append.get(2, TimeUnit.SECONDS).durableThrough())
                        .contains(third.eventId());
            } finally {
                operations.releaseVerification.countDown();
            }
        }
    }

    @Test
    void catalogPublicationUsesOnlyABoundedSessionLockSection() throws Exception {
        BlockingCatalogPublicationOperations operations = new BlockingCatalogPublicationOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations);
                ExecutorService appender = Executors.newSingleThreadExecutor()) {
            storage.append(SESSION, List.of(first, second));
            assertThat(operations.publicationStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<JournalAppendResult> append =
                    appender.submit(() -> storage.append(SESSION, List.of(third)));
            assertThatExceptionOfType(TimeoutException.class)
                    .isThrownBy(() -> append.get(200, TimeUnit.MILLISECONDS));

            operations.releasePublication.countDown();
            assertThat(append.get(10, TimeUnit.SECONDS).durableThrough())
                    .contains(third.eventId());
        } finally {
            operations.releasePublication.countDown();
        }
    }

    @Test
    void temporaryCreationIsAtomicWithTransitionRegistration() throws Exception {
        BlockingTemporaryOpenOperations operations = new BlockingTemporaryOpenOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations);
                ExecutorService appender = Executors.newSingleThreadExecutor()) {
            storage.append(SESSION, List.of(first, second));
            assertThat(operations.temporaryOpened.await(10, TimeUnit.SECONDS)).isTrue();

            CountDownLatch appendStarted = new CountDownLatch(1);
            Future<JournalAppendResult> append = appender.submit(() -> {
                appendStarted.countDown();
                return storage.append(SESSION, List.of(third));
            });
            assertThat(appendStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatExceptionOfType(TimeoutException.class)
                    .isThrownBy(() -> append.get(200, TimeUnit.MILLISECONDS));

            operations.releaseTemporary.countDown();
            assertThat(append.get(10, TimeUnit.SECONDS).durableThrough())
                    .contains(third.eventId());
        }
    }

    @Test
    void rejectsARegisteredPresentTemporaryThatDisappears() throws Exception {
        BlockingTemporaryOpenOperations operations = new BlockingTemporaryOpenOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations)) {
            storage.append(SESSION, List.of(first, second));
            assertThat(operations.temporaryOpened.await(10, TimeUnit.SECONDS)).isTrue();
            Files.delete(root.resolve("session-1/00000001.cbor.zst.tmp"));
            operations.releaseTemporary.countDown();

            assertIoFailure(() -> storage.append(SESSION, List.of(third)));
            storage.awaitMaintenanceForTest();
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        } finally {
            operations.releaseTemporary.countDown();
        }
    }

    @Test
    void postCreatePreIdentityFailureLeavesATemporaryRegisteredForSafeCleanup() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        FailAfterTemporaryCreationOperations operations =
                new FailAfterTemporaryCreationOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor)) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            executor.runNext();
            Path temporary = root.resolve("session-1/00000001.cbor.zst.tmp");
            assertThat(Files.exists(temporary)).isTrue();

            assertThat(storage.append(SESSION, List.of(third)).durableThrough())
                    .contains(third.eventId());
            executor.awaitPending(1);
            executor.runAll();

            assertThat(Files.exists(temporary)).isFalse();
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        }
    }

    @Test
    void doubleIdentityFailureKeepsTemporaryReservedUntilCleanup() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        DoubleTemporaryIdentityFailureOperations operations =
                new DoubleTemporaryIdentityFailureOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        Path temporary = root.resolve("session-1/00000001.cbor.zst.tmp");

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor)) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            executor.runNext();
            assertThat(Files.exists(temporary)).isTrue();
            assertThat(operations.identityCaptureFailures).isOne();

            assertThat(storage.append(SESSION, List.of(third)).durableThrough())
                    .contains(third.eventId());
            executor.awaitPending(1);
            executor.runAll();

            assertThat(Files.exists(temporary)).isFalse();
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        }
    }

    @Test
    void absentUnboundTemporaryDoesNotPoisonAppend() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        DoubleTemporaryIdentityFailureOperations operations =
                new DoubleTemporaryIdentityFailureOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        Path temporary = root.resolve("session-1/00000001.cbor.zst.tmp");

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor)) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            executor.runNext();
            Files.delete(temporary);

            assertThat(storage.append(SESSION, List.of(third)).durableThrough())
                    .contains(third.eventId());
            executor.awaitPending(1);
            executor.runAll();

            assertThat(names(SESSION)).containsExactly(
                    "00000001.cbor.zst", "00000002.cbor.zst", "00000003.cbor");
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        }
    }

    @Test
    void replacementAfterUnboundTemporaryBindingIsNotDeleted() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        DoubleTemporaryIdentityFailureOperations operations =
                new DoubleTemporaryIdentityFailureOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        Path temporary = root.resolve("session-1/00000001.cbor.zst.tmp");

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor)) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            executor.runNext();
            assertThat(storage.append(SESSION, List.of(third)).durableThrough())
                    .contains(third.eventId());

            byte[] replacement = {9, 8, 7};
            replaceIdentity(temporary, replacement);
            executor.runAll();

            assertThat(Files.readAllBytes(temporary)).isEqualTo(replacement);
            assertThat(storage.maintenanceFailureForTest()).isPresent();
        }
    }

    @Test
    void sourceTransitionIsRegisteredBeforeCatalogSwitchBecomesVisible() throws Exception {
        BlockingSourceDeleteOperations operations = new BlockingSourceDeleteOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations);
                ExecutorService appender = Executors.newSingleThreadExecutor()) {
            storage.append(SESSION, List.of(first, second));
            assertThat(operations.sourceDeleteStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<JournalAppendResult> append =
                    appender.submit(() -> storage.append(SESSION, List.of(third)));
            try {
                assertThat(append.get(2, TimeUnit.SECONDS).durableThrough())
                        .contains(third.eventId());
            } finally {
                operations.releaseSourceDelete.countDown();
            }
        }
    }

    @Test
    void retriesDirectoryForceAfterAnIdentityBoundUnlinkAlreadySucceeded() throws Exception {
        FailFirstCleanupDirectoryForceOperations operations =
                new FailFirstCleanupDirectoryForceOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = directStorage(
                segmentConfig(first.encodedRecord().size()), operations)) {
            storage.append(SESSION, List.of(first, second));
            assertThat(operations.failedForceStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(storage.append(SESSION, List.of(third)).durableThrough())
                    .contains(third.eventId());
            operations.releaseFailedForce.countDown();
            storage.awaitMaintenanceForTest();
            assertThat(operations.failedCleanupForce).isTrue();
            assertThat(operations.cleanupUnlinks).isOne();

            storage.lastEventId(SESSION);
            storage.awaitMaintenanceForTest();

            assertThat(operations.cleanupUnlinks).isOne();
            assertThat(operations.successfulRetryForce).isTrue();
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
        } finally {
            operations.releaseFailedForce.countDown();
        }
    }

    @Test
    void retriesAnIdentityBoundUnlinkThatFailedBeforeDeletion() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        FailingSourceUnlinkOperations operations = new FailingSourceUnlinkOperations(false);
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor)) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            executor.runNext();
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isTrue();

            storage.lastEventId(SESSION);
            executor.awaitPending(1);
            executor.runNext();

            assertThat(operations.sourceDeleteAttempts).isEqualTo(2);
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isFalse();
        }
    }

    @Test
    void completesCleanupWhenDeleteFailedAfterPhysicalUnlink() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        FailingSourceUnlinkOperations operations = new FailingSourceUnlinkOperations(true);
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor)) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            executor.runNext();
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isFalse();

            storage.lastEventId(SESSION);
            executor.awaitPending(1);
            executor.runNext();
            assertThat(operations.sourceDeleteAttempts).isOne();

            storage.lastEventId(SESSION);
            storage.awaitMaintenanceForTest();
        }
    }

    @Test
    void queuedCompressionDoesNotDelayOrWeakenAppendDurability() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        BarrierOperations operations = new BarrierOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, operations, executor)) {
            JournalAppendResult result = storage.append(SESSION, List.of(first, second));

            assertThat(result.durableThrough()).contains(second.eventId());
            executor.awaitPending(1);
            assertThat(executor.pending()).isOne();
            assertThat(operations.fileForces).isPositive();
            assertThat(operations.directoryForces).isPositive();
            assertThat(names(SESSION)).containsExactly("00000001.cbor", "00000002.cbor");

            executor.runNext();

            assertThat(storage.maintenanceFailureForTest()).isEmpty();
            assertThat(names(SESSION)).containsExactly("00000001.cbor.zst", "00000002.cbor");
        }
    }

    @Test
    void aNewerAcceptedRequestIsRedispatchedAfterTheCurrentAttemptFails() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        FailingConcurrentEnqueueOperations operations = new FailingConcurrentEnqueueOperations();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root,
                segmentConfig(first.encodedRecord().size()),
                operations,
                executor);
                ExecutorService maintenanceRunner = Executors.newSingleThreadExecutor()) {
            storage.append(SESSION, List.of(first, second));
            executor.awaitPending(1);
            Future<?> failedAttempt = maintenanceRunner.submit(executor::runNext);
            assertThat(operations.failureStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(storage.append(SESSION, List.of(third)).durableThrough())
                    .contains(third.eventId());
            operations.releaseFailure.countDown();
            failedAttempt.get(10, TimeUnit.SECONDS);

            executor.awaitPending(1);
            executor.runAll();
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(first, second, third);
            assertThat(names(SESSION)).contains("00000001.cbor.zst");
        } finally {
            operations.releaseFailure.countDown();
        }
    }

    @Test
    void executorDispatchThatBlocksCannotDelayAppend() throws Exception {
        BlockingDispatchExecutor executor = new BlockingDispatchExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, new DurableFileOperations(), executor)) {
            JournalAppendResult result = storage.append(SESSION, List.of(first, second));

            assertThat(result.durableThrough()).contains(second.eventId());
            assertThat(executor.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(names(SESSION)).containsExactly("00000001.cbor", "00000002.cbor");

            executor.release.countDown();
            storage.awaitMaintenanceForTest();
            assertThat(names(SESSION)).containsExactly("00000001.cbor.zst", "00000002.cbor");
        }
    }

    @Test
    void closeCancelsDispatchedButNotStartedCompression() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());
        var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, new DurableFileOperations(), executor);
        storage.append(SESSION, List.of(first, second));
        executor.awaitPending(1);

        storage.close();
        executor.runAll();

        assertThat(names(SESSION)).containsExactly("00000001.cbor", "00000002.cbor");
    }

    @Test
    void boundedMaintenanceOverflowNeverPoisonsAnAppend() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        JournalStorageConfig config = segmentConfig(event(1).encodedRecord().size());
        SessionId firstSession = new SessionId("first");
        SessionId overflowSession = new SessionId("overflow");

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, new DurableFileOperations(), executor, 1)) {
            assertThat(storage.append(firstSession, List.of(event(1), event(2))).durableThrough())
                    .contains(new EventId(2));
            assertThat(storage.append(overflowSession, List.of(event(3), event(4))).durableThrough())
                    .contains(new EventId(4));

            executor.awaitPending(1);
            assertThat(executor.pending()).isOne();
            assertThat(storage.maintenanceFailureForTest()).isPresent();
            assertThat(Files.exists(root.resolve("overflow/00000001.cbor"))).isTrue();
            assertThat(storage.lastEventId(overflowSession)).contains(new EventId(4));
        }
    }

    @Test
    void sourceDeletionWaitsForAReadSnapshotThatReferencesIt() throws Exception {
        CapturingExecutor maintenanceExecutor = new CapturingExecutor();
        BlockingReadOperations operations = new BlockingReadOperations();
        SessionEventRecord first = randomEvent(1, 100_000);
        SessionEventRecord second = event(2);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, operations, maintenanceExecutor);
                ExecutorService readerExecutor = Executors.newSingleThreadExecutor()) {
            storage.append(SESSION, List.of(first, second));
            maintenanceExecutor.awaitPending(1);
            var read = readerExecutor.submit(() -> storage.readAfter(SESSION, Optional.empty()));
            assertThat(operations.readStarted.await(10, TimeUnit.SECONDS)).isTrue();

            maintenanceExecutor.runNext();

            assertThat(Files.exists(root.resolve("session-1/00000001.cbor.zst"))).isTrue();
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isTrue();
            operations.releaseRead.countDown();
            assertThat(read.get(10, TimeUnit.SECONDS).records()).containsExactly(first, second);
            maintenanceExecutor.runAll();
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isFalse();
        }
    }

    @Test
    void aNewCompressedSnapshotDoesNotKeepAnOldSourceLeaseAlive() throws Exception {
        CapturingExecutor maintenanceExecutor = new CapturingExecutor();
        OverlappingReadOperations operations = new OverlappingReadOperations();
        SessionEventRecord first = randomEvent(1, 100_000);
        SessionEventRecord second = event(2);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, operations, maintenanceExecutor);
                ExecutorService oldReader = namedReader("old-source-reader");
                ExecutorService newReader = namedReader("new-compressed-reader")) {
            storage.append(SESSION, List.of(first, second));
            maintenanceExecutor.awaitPending(1);
            Future<JournalReadResult> oldRead = oldReader.submit(
                    () -> storage.readAfter(SESSION, Optional.empty()));
            assertThat(operations.oldReadStarted.await(10, TimeUnit.SECONDS)).isTrue();

            maintenanceExecutor.runNext();
            assertThat(Files.exists(root.resolve("session-1/00000001.cbor.zst"))).isTrue();
            Future<JournalReadResult> newRead = newReader.submit(
                    () -> storage.readAfter(SESSION, Optional.empty()));
            assertThat(operations.newReadStarted.await(10, TimeUnit.SECONDS)).isTrue();

            operations.releaseOldRead.countDown();
            assertThat(oldRead.get(10, TimeUnit.SECONDS).records()).containsExactly(first, second);
            maintenanceExecutor.awaitPending(1);
            maintenanceExecutor.runAll();

            assertThat(Files.exists(root.resolve("session-1/00000001.cbor"))).isFalse();
            operations.releaseNewRead.countDown();
            assertThat(newRead.get(10, TimeUnit.SECONDS).records()).containsExactly(first, second);
        } finally {
            operations.releaseOldRead.countDown();
            operations.releaseNewRead.countDown();
        }
    }

    private FileSystemSessionJournalStorage directStorage(
            JournalStorageConfig config,
            DurableFileOperations operations) {
        return FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, operations, Runnable::run);
    }

    private static ExecutorService namedReader(String name) {
        return Executors.newSingleThreadExecutor(task -> new Thread(task, name));
    }

    private void assertStoredCorruption() throws Exception {
        assertStoredCorruption(segmentConfig(1));
    }

    private void assertStoredCorruption(JournalStorageConfig config) throws Exception {
        try (var storage = directStorage(config, new DurableFileOperations())) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.firstEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.STORED_CORRUPTION);
        }
    }

    private JournalStorageConfig segmentConfig(long targetBytes) {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults();
        return new JournalStorageConfig(
                limits,
                limits.maxCollectionEntries(),
                limits.maxMessageBytes(),
                targetBytes);
    }

    private JournalStorageConfig boundedConfig(
            AgentProtocolLimits limits,
            long logicalLimit,
            long windowLimit) {
        return new JournalStorageConfig(
                limits,
                10,
                512 * 1024,
                Long.MAX_VALUE,
                logicalLimit,
                windowLimit);
    }

    private SessionEventRecord randomEvent(long id, int bytes) throws Exception {
        byte[] random = new byte[bytes];
        new Random(id).nextBytes(random);
        byte[] encoded = CODEC.encode(
                new EventId(id),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(random)));
        return CODEC.decode(encoded);
    }

    private SessionEventRecord repetitiveEvent(long id, int bytes) throws Exception {
        return repetitiveEvent(AgentProtocolLimits.defaults(), id, bytes);
    }

    private SessionEventRecord repetitiveEvent(
            AgentProtocolLimits limits,
            long id,
            int bytes) throws Exception {
        byte[] repetitive = new byte[bytes];
        Arrays.fill(repetitive, (byte) 0x5a);
        SessionEventCodec codec = new SessionEventCodec(limits);
        byte[] encoded = codec.encode(
                new EventId(id),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(repetitive)));
        return codec.decode(encoded);
    }

    private void writeCompressed(
            int number,
            List<SessionEventRecord> records,
            Integer windowLog) throws IOException {
        Path directory = Files.createDirectories(root.resolve(SESSION.value()));
        Path path = directory.resolve("%08d.cbor.zst".formatted(number));
        try (ZstdOutputStream output = new ZstdOutputStream(Files.newOutputStream(path))) {
            if (windowLog != null) {
                output.setWindowLog(windowLog);
            }
            for (SessionEventRecord record : records) {
                output.write(record.encodedRecord().toByteArray());
            }
        }
    }

    private static byte[] compressedFrame(SessionEventRecord record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream output = new ZstdCompressorOutputStream(bytes)) {
            output.write(record.encodedRecord().toByteArray());
        }
        return bytes.toByteArray();
    }

    private void assertIoFailure(ThrowingAction action) {
        assertThatExceptionOfType(JournalStorageException.class)
                .isThrownBy(action::run)
                .extracting(JournalStorageException::reason)
                .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
    }

    private void replaceIdentity(Path target, byte[] bytes) throws IOException {
        Path replacement = target.resolveSibling(target.getFileName() + ".replacement");
        Files.write(replacement, bytes);
        Files.move(
                replacement,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeDual(SessionId sessionId, int number, byte[] bytes) throws IOException {
        writeDual(sessionId, number, bytes, bytes);
    }

    private void writeDual(
            SessionId sessionId,
            int number,
            byte[] source,
            byte[] compressedLogicalBytes) throws IOException {
        Path directory = Files.createDirectories(root.resolve(sessionId.value()));
        Files.write(directory.resolve("%08d.cbor".formatted(number)), source);
        try (OutputStream output = new ZstdCompressorOutputStream(Files.newOutputStream(
                directory.resolve("%08d.cbor.zst".formatted(number))))) {
            output.write(compressedLogicalBytes);
        }
    }

    private List<String> names(SessionId sessionId) throws IOException {
        try (var entries = Files.list(root.resolve(sessionId.value()))) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private enum CompressionFailurePoint {
        BEFORE_TEMP_FORCE,
        BEFORE_PUBLICATION,
        AFTER_PUBLICATION
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class FailingCompressionOperations extends DurableFileOperations {
        private final CompressionFailurePoint point;
        private final AtomicBoolean failed = new AtomicBoolean();

        private FailingCompressionOperations(CompressionFailurePoint point) {
            this.point = point;
        }

        @Override
        void beforeCompressionTempForce(Path path) throws IOException {
            failAt(CompressionFailurePoint.BEFORE_TEMP_FORCE);
        }

        @Override
        void afterCompressionTempVerified(Path path) throws IOException {
            failAt(CompressionFailurePoint.BEFORE_PUBLICATION);
        }

        @Override
        void afterCompressionPublished(Path path) throws IOException {
            failAt(CompressionFailurePoint.AFTER_PUBLICATION);
        }

        private void failAt(CompressionFailurePoint candidate) throws IOException {
            if (point == candidate && failed.compareAndSet(false, true)) {
                throw new IOException("injected compression failure at " + point);
            }
        }
    }

    private static final class RacingPublicationOperations extends DurableFileOperations {
        private final byte[] competitor;

        private RacingPublicationOperations(byte[] competitor) {
            this.competitor = competitor.clone();
        }

        @Override
        void beforeCompressionPublication(Path temporary, Path target) throws IOException {
            Files.write(target, competitor);
        }
    }

    private static final class BlockingFinalVerificationOperations extends DurableFileOperations {
        private final CountDownLatch verificationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseVerification = new CountDownLatch(1);
        private final AtomicBoolean blockNextCompressedRead = new AtomicBoolean();

        @Override
        void afterCompressionPublished(Path path) {
            blockNextCompressedRead.set(true);
        }

        @Override
        void contentBytesRead(Path path, int count) {
            if (!path.getFileName().toString().endsWith(".cbor.zst")
                    || !blockNextCompressedRead.compareAndSet(true, false)) {
                return;
            }
            verificationStarted.countDown();
            awaitUnchecked(releaseVerification, "final verification");
        }
    }

    private static final class BlockingTemporaryOpenOperations extends DurableFileOperations {
        private final CountDownLatch temporaryOpened = new CountDownLatch(1);
        private final CountDownLatch releaseTemporary = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();

        @Override
        void afterAppendOpen(Path path, FileChannel channel) throws IOException {
            if (!path.getFileName().toString().endsWith(".cbor.zst.tmp")
                    || !blocked.compareAndSet(false, true)) {
                return;
            }
            temporaryOpened.countDown();
            await(releaseTemporary, "temporary registration");
        }
    }

    private static final class BlockingCatalogPublicationOperations extends DurableFileOperations {
        private final CountDownLatch publicationStarted = new CountDownLatch(1);
        private final CountDownLatch releasePublication = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();

        @Override
        void beforeCompressionCatalogPublication() throws IOException {
            if (!blocked.compareAndSet(false, true)) {
                return;
            }
            publicationStarted.countDown();
            await(releasePublication, "catalog publication");
        }
    }

    private static final class FailAfterTemporaryCreationOperations extends DurableFileOperations {
        private final AtomicBoolean failed = new AtomicBoolean();

        @Override
        void afterAppendFileCreated(Path path) throws IOException {
            if (path.getFileName().toString().endsWith(".cbor.zst.tmp")
                    && failed.compareAndSet(false, true)) {
                throw new IOException("injected failure after temporary creation");
            }
        }
    }

    private static final class DoubleTemporaryIdentityFailureOperations
            extends DurableFileOperations {
        private final AtomicBoolean creationFailed = new AtomicBoolean();
        private final AtomicBoolean identityCaptureFailed = new AtomicBoolean();
        private int identityCaptureFailures;

        @Override
        void afterAppendFileCreated(Path path) throws IOException {
            if (path.getFileName().toString().endsWith(".cbor.zst.tmp")
                    && creationFailed.compareAndSet(false, true)) {
                throw new IOException("injected failure after temporary creation");
            }
        }

        @Override
        void beforeIdentityCapture(Path path) throws IOException {
            if (path.getFileName().toString().endsWith(".cbor.zst.tmp")
                    && identityCaptureFailed.compareAndSet(false, true)) {
                identityCaptureFailures++;
                throw new IOException("injected fallback identity capture failure");
            }
        }
    }

    private static final class BlockingSourceDeleteOperations extends DurableFileOperations {
        private final CountDownLatch sourceDeleteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSourceDelete = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();

        @Override
        boolean delete(CleanupToken cleanup) throws IOException {
            if (cleanup.path().getFileName().toString().equals("00000001.cbor")
                    && blocked.compareAndSet(false, true)) {
                sourceDeleteStarted.countDown();
                await(releaseSourceDelete, "source deletion");
            }
            return super.delete(cleanup);
        }
    }

    private static final class FailFirstCleanupDirectoryForceOperations extends DurableFileOperations {
        private final CountDownLatch failedForceStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFailedForce = new CountDownLatch(1);
        private boolean cleanupUnlinked;
        private boolean failedCleanupForce;
        private boolean successfulRetryForce;
        private int cleanupUnlinks;

        @Override
        boolean delete(CleanupToken cleanup) throws IOException {
            boolean source = cleanup.path().getFileName().toString().equals("00000001.cbor");
            boolean deleted = super.delete(cleanup);
            if (source && deleted) {
                cleanupUnlinks++;
                cleanupUnlinked = true;
            }
            return deleted;
        }

        @Override
        void forceDirectoryChannel(DurableDirectory directory, FileChannel channel)
                throws IOException {
            if (cleanupUnlinked && !failedCleanupForce) {
                failedCleanupForce = true;
                failedForceStarted.countDown();
                await(releaseFailedForce, "failed cleanup directory force");
                throw new IOException("injected directory force failure after cleanup unlink");
            }
            if (failedCleanupForce && cleanupUnlinked) {
                successfulRetryForce = true;
            }
            super.forceDirectoryChannel(directory, channel);
        }
    }

    private static final class FailingSourceUnlinkOperations extends DurableFileOperations {
        private final boolean failAfterUnlink;
        private final AtomicBoolean failed = new AtomicBoolean();
        private int sourceDeleteAttempts;

        private FailingSourceUnlinkOperations(boolean failAfterUnlink) {
            this.failAfterUnlink = failAfterUnlink;
        }

        @Override
        boolean delete(CleanupToken cleanup) throws IOException {
            if (!cleanup.path().getFileName().toString().equals("00000001.cbor")) {
                return super.delete(cleanup);
            }
            sourceDeleteAttempts++;
            if (failed.compareAndSet(false, true)) {
                if (failAfterUnlink) {
                    super.delete(cleanup);
                }
                throw new IOException("injected source unlink failure");
            }
            return super.delete(cleanup);
        }
    }

    private static final class CapturingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
            notifyAll();
        }

        private synchronized int pending() {
            return tasks.size();
        }

        private synchronized void awaitPending(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (tasks.size() < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("Timed out waiting for maintenance dispatch");
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.remove();
            }
            task.run();
        }

        private void runAll() {
            while (pending() > 0) {
                runNext();
            }
        }
    }

    private static void await(CountDownLatch latch, String operation) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting to release " + operation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(operation + " interrupted", e);
        }
    }

    private static void awaitUnchecked(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release " + operation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", e);
        }
    }

    private static final class BarrierOperations extends DurableFileOperations {
        private int fileForces;
        private int directoryForces;

        @Override
        void forceFile(FileChannel channel) throws IOException {
            fileForces++;
            super.forceFile(channel);
        }

        @Override
        void forceDirectoryChannel(DurableDirectory directory, FileChannel channel)
                throws IOException {
            directoryForces++;
            super.forceDirectoryChannel(directory, channel);
        }
    }

    private static final class FailingConcurrentEnqueueOperations extends DurableFileOperations {
        private final CountDownLatch failureStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFailure = new CountDownLatch(1);
        private final AtomicBoolean failed = new AtomicBoolean();

        @Override
        void beforeCompressionTempForce(Path path) throws IOException {
            if (!failed.compareAndSet(false, true)) {
                return;
            }
            failureStarted.countDown();
            await(releaseFailure, "compression failure");
            throw new IOException("injected first maintenance attempt failure");
        }
    }

    private static final class BlockingDispatchExecutor implements Executor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release executor");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("executor interrupted", e);
            }
            command.run();
        }
    }

    private static final class BlockingReadOperations extends DurableFileOperations {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        @Override
        void beforeContentRead(Path path) throws IOException {
            if (!Thread.currentThread().getName().contains("pool-")) {
                return;
            }
            readStarted.countDown();
            try {
                if (!releaseRead.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release read");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("read interrupted", e);
            }
        }
    }

    private static final class OverlappingReadOperations extends DurableFileOperations {
        private final CountDownLatch oldReadStarted = new CountDownLatch(1);
        private final CountDownLatch newReadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseOldRead = new CountDownLatch(1);
        private final CountDownLatch releaseNewRead = new CountDownLatch(1);
        private final AtomicBoolean blockedOld = new AtomicBoolean();
        private final AtomicBoolean blockedNew = new AtomicBoolean();

        @Override
        void beforeContentRead(Path path) throws IOException {
            String thread = Thread.currentThread().getName();
            String file = path.getFileName().toString();
            if (thread.equals("old-source-reader")
                    && file.equals("00000001.cbor")
                    && blockedOld.compareAndSet(false, true)) {
                oldReadStarted.countDown();
                await(releaseOldRead, "old source read");
            }
            if (thread.equals("new-compressed-reader")
                    && file.equals("00000001.cbor.zst")
                    && blockedNew.compareAndSet(false, true)) {
                newReadStarted.countDown();
                await(releaseNewRead, "new compressed read");
            }
        }
    }
}
