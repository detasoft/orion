package pro.deta.orion.agent.server.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventDecoder;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.encoded;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.event;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.opaqueEvent;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.writeCompressedSegment;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.writeSegment;

class FileSystemSessionJournalStorageTest {
    private static final SessionId SESSION = new SessionId("session-1");

    @TempDir
    Path root;

    @Test
    void rotatesOnlyBetweenRecords() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        SessionEventRecord fourth = event(4);
        SessionEventRecord fifth = event(5);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = new FileSystemSessionJournalStorage(
                root, config, new DurableFileOperations())) {
            JournalAppendResult batch = storage.append(SESSION, List.of(first, second, third));

            assertThat(batch.newlyStored()).containsExactly(first, second, third);
            assertThat(segmentNames(SESSION)).containsExactly(
                    "00000001.cbor", "00000002.cbor", "00000003.cbor");
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(first));
            assertThat(Files.readAllBytes(root.resolve("session-1/00000002.cbor")))
                    .isEqualTo(encoded(second));
            assertThat(Files.readAllBytes(root.resolve("session-1/00000003.cbor")))
                    .isEqualTo(encoded(third));

            storage.append(SESSION, List.of(fourth));

            assertThat(segmentNames(SESSION)).containsExactly(
                    "00000001.cbor", "00000002.cbor", "00000003.cbor", "00000004.cbor");
            assertThat(Files.readAllBytes(root.resolve("session-1/00000004.cbor")))
                    .isEqualTo(encoded(fourth));
        }

        try (var recovered = new FileSystemSessionJournalStorage(
                root, config, new DurableFileOperations())) {
            recovered.append(SESSION, List.of(fifth));

            assertThat(segmentNames(SESSION)).containsExactly(
                    "00000001.cbor",
                    "00000002.cbor",
                    "00000003.cbor",
                    "00000004.cbor",
                    "00000005.cbor");
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .extracting(SessionEventRecord::eventId)
                    .containsExactly(
                            new EventId(1),
                            new EventId(2),
                            new EventId(3),
                            new EventId(4),
                            new EventId(5));
        }
    }

    @Test
    void keepsOneOversizedRecordWhole() throws Exception {
        SessionEventRecord oversized = event(1);
        SessionEventRecord following = event(2);
        JournalStorageConfig config = segmentConfig(oversized.encodedRecord().size() - 1L);

        try (var storage = new FileSystemSessionJournalStorage(
                root, config, new DurableFileOperations())) {
            storage.append(SESSION, List.of(oversized, following));

            assertThat(segmentNames(SESSION)).containsExactly("00000001.cbor", "00000002.cbor");
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(oversized.encodedRecord().toByteArray());
            assertThat(Files.readAllBytes(root.resolve("session-1/00000002.cbor")))
                    .isEqualTo(following.encodedRecord().toByteArray());
            assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                    .containsExactly(oversized, following);
        }
    }

    @Test
    void readsAfterAcrossSegmentsAndReportsGaps() throws Exception {
        SessionEventRecord first = event(10);
        SessionEventRecord second = event(20);
        SessionEventRecord third = event(30);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());

        try (var storage = new FileSystemSessionJournalStorage(
                root, config, new DurableFileOperations())) {
            storage.append(SESSION, List.of(first, second, third));

            JournalReadResult beforeFirst = storage.readAfter(SESSION, Optional.of(new EventId(5)));
            assertThat(beforeFirst.gap()).contains(new JournalGap(new EventId(5), new EventId(10)));
            assertThat(beforeFirst.records()).containsExactly(first, second, third);
            assertThat(storage.readAfter(SESSION, Optional.of(new EventId(10))).records())
                    .containsExactly(second, third);
            JournalReadResult between = storage.readAfter(SESSION, Optional.of(new EventId(15)));
            assertThat(between.gap()).isEmpty();
            assertThat(between.records()).containsExactly(second, third);
            JournalReadResult afterLast = storage.readAfter(SESSION, Optional.of(new EventId(30)));
            assertThat(afterLast.gap()).isEmpty();
            assertThat(afterLast.records()).isEmpty();
        }

        Files.delete(root.resolve("session-1/00000001.cbor"));
        try (var recovered = new FileSystemSessionJournalStorage(
                root, config, new DurableFileOperations())) {
            assertThat(recovered.firstEventId(SESSION)).contains(new EventId(20));
            JournalReadResult retained = recovered.readAfter(
                    SESSION, Optional.of(new EventId(5)));
            assertThat(retained.gap()).contains(new JournalGap(new EventId(5), new EventId(20)));
            assertThat(retained.records()).containsExactly(second, third);
        }

        writeSegment(root, "later-gap", 1, event(1));
        writeSegment(root, "later-gap", 3, event(3));
        try (var recovered = new FileSystemSessionJournalStorage(
                root, config, new DurableFileOperations())) {
            assertStoredCorruption(recovered, "later-gap");
        }
    }

    @Test
    void recoversOnlyAnIncompleteActiveTail() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        Path sessionDirectory = Files.createDirectories(root.resolve(SESSION.value()));
        Path active = sessionDirectory.resolve("00000001.cbor");
        byte[] incompleteSecond = Arrays.copyOf(
                second.encodedRecord().toByteArray(), second.encodedRecord().size() - 1);
        byte[] damaged = new byte[first.encodedRecord().size() + incompleteSecond.length];
        System.arraycopy(first.encodedRecord().toByteArray(), 0, damaged, 0, first.encodedRecord().size());
        System.arraycopy(
                incompleteSecond,
                0,
                damaged,
                first.encodedRecord().size(),
                incompleteSecond.length);
        Files.write(active, damaged);

        try (var recovered = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertThat(recovered.lastEventId(SESSION)).contains(first.eventId());
            assertThat(Files.size(active)).isEqualTo(first.encodedRecord().size());
            assertThat(Files.readAllBytes(active)).isEqualTo(first.encodedRecord().toByteArray());

            recovered.append(SESSION, List.of(second));

            assertThat(Files.readAllBytes(active)).isEqualTo(encoded(first, second));
        }

        SessionId pendingOnly = new SessionId("pending-only");
        Path pendingDirectory = Files.createDirectories(root.resolve(pendingOnly.value()));
        Path pendingActive = pendingDirectory.resolve("00000001.cbor");
        Files.write(pendingActive, incompleteSecond);
        try (var recovered = new FileSystemSessionJournalStorage(root, testConfig())) {
            JournalAppendResult appended = recovered.append(pendingOnly, List.of(first));

            assertThat(appended.newlyStored()).containsExactly(first);
            assertThat(Files.readAllBytes(pendingActive)).isEqualTo(first.encodedRecord().toByteArray());
        }

        byte[] malformed = first.encodedRecord().toByteArray();
        malformed[0] = (byte) 0xff;
        Path malformedDirectory = Files.createDirectories(root.resolve("malformed-active"));
        Files.write(malformedDirectory.resolve("00000001.cbor"), malformed);
        try (var recovered = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertStoredCorruption(recovered, "malformed-active");
        }
    }

    @Test
    void rejectsCompleteSemanticDecoderFailureAsStoredCorruption() throws Exception {
        SessionId session = new SessionId("semantic-rejection");
        byte[] invalidRecord = new byte[]{(byte) 0x82, 0x01, 0x19, 0x01, 0x00};
        Path directory = Files.createDirectories(root.resolve(session.value()));
        Files.write(directory.resolve("00000001.cbor"), invalidRecord);

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertStoredCorruption(storage, session.value());
        }
    }

    @Test
    void opensEmptyAndExistingJournals() throws Exception {
        SessionEventRecord tailFirst = event(10);
        SessionEventRecord tailSecond = event(11);
        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            writeSegment(root, SESSION.value(), 1, event(1), event(2), event(3));
            writeSegment(root, "active-tail", 3, tailFirst);
            byte[] withIncompleteTail = encoded(tailFirst, tailSecond);
            Files.write(
                    root.resolve("active-tail/00000003.cbor"),
                    Arrays.copyOf(withIncompleteTail, withIncompleteTail.length - 1));

            assertThat(storage.firstEventId(new SessionId("missing"))).isEmpty();
            assertThat(storage.lastEventId(new SessionId("missing"))).isEmpty();
            assertThat(storage.readAfter(new SessionId("missing"), Optional.of(new EventId(1))).records())
                    .isEmpty();
            assertThat(storage.readAfter(new SessionId("missing"), Optional.of(new EventId(1))).gap())
                    .isEmpty();
            assertThat(storage.firstEventId(SESSION)).contains(new EventId(1));
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(3));
            assertThat(storage.firstEventId(new SessionId("active-tail"))).contains(new EventId(10));
            assertThat(storage.lastEventId(new SessionId("active-tail"))).contains(new EventId(10));
            assertThat(storage.readAfter(new SessionId("active-tail"), Optional.empty()).records())
                    .extracting(SessionEventRecord::eventId)
                    .containsExactly(new EventId(10));
        }
    }

    @Test
    void preservesOpaqueRecordsOnRead() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord opaque = opaqueEvent(2);
        SessionEventRecord last = event(3);
        writeSegment(root, SESSION.value(), 1, first, opaque, last);

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            JournalReadResult all = storage.readAfter(SESSION, Optional.empty());
            JournalReadResult afterFirst = storage.readAfter(SESSION, Optional.of(new EventId(1)));

            assertThat(all.gap()).isEmpty();
            assertThat(all.records()).extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            first.encodedRecord().toByteArray(),
                            opaque.encodedRecord().toByteArray(),
                            last.encodedRecord().toByteArray());
            assertThat(all.records().get(1).eventType()).isEqualTo(0x7ffe);
            assertThat(all.records().get(1).trailingFieldCount()).isOne();
            assertThat(afterFirst.gap()).isEmpty();
            assertThat(afterFirst.records()).extracting(SessionEventRecord::eventId)
                    .containsExactly(new EventId(2), new EventId(3));
            assertThat(storage.readAfter(SESSION, Optional.of(new EventId(3))).records()).isEmpty();
        }
    }

    @Test
    void appendsBatchesAndRecoversThemAfterRestart() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord opaque = opaqueEvent(2);
        SessionEventRecord third = event(3);

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            JournalAppendResult firstAppend = storage.append(SESSION, List.of(first, opaque));

            assertThat(firstAppend.durableThrough()).contains(new EventId(2));
            assertThat(firstAppend.newlyStored()).containsExactly(first, opaque);
            assertThat(firstAppend.newlyStored())
                    .extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            first.encodedRecord().toByteArray(),
                            opaque.encodedRecord().toByteArray());
            assertThat(storage.firstEventId(SESSION)).contains(new EventId(1));
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(2));
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(first, opaque));

            JournalAppendResult emptyAppend = storage.append(SESSION, List.of());

            assertThat(emptyAppend.durableThrough()).contains(new EventId(2));
            assertThat(emptyAppend.newlyStored()).isEmpty();

            JournalAppendResult laterAppend = storage.append(SESSION, List.of(third));

            assertThat(laterAppend.durableThrough()).contains(new EventId(3));
            assertThat(laterAppend.newlyStored()).containsExactly(third);
            assertThat(laterAppend.newlyStored().getFirst().encodedRecord().toByteArray())
                    .isEqualTo(third.encodedRecord().toByteArray());
            assertThat(storage.lastEventId(SESSION)).contains(new EventId(3));
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(first, opaque, third));
        }

        try (var recovered = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertThat(recovered.firstEventId(SESSION)).contains(new EventId(1));
            assertThat(recovered.lastEventId(SESSION)).contains(new EventId(3));
            assertThat(recovered.readAfter(SESSION, Optional.empty()).records())
                    .extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            first.encodedRecord().toByteArray(),
                            opaque.encodedRecord().toByteArray(),
                            third.encodedRecord().toByteArray());
        }
    }

    @Test
    void skipsIdenticalRetryOverlap() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        SessionEventRecord fourth = event(4);
        SessionEventRecord fifth = event(5);
        List<SessionEventRecord> retry = List.of(second, third, fourth, fifth);
        SessionId recoveredSession = new SessionId("recovered-overlap");
        writeSegment(root, recoveredSession.value(), 1, first);
        writeSegment(root, recoveredSession.value(), 2, second, third);
        CountingFileOperations cachedOperations = new CountingFileOperations();
        JournalAppendResult cachedResult;

        try (var storage = new FileSystemSessionJournalStorage(
                root, testConfig(), cachedOperations)) {
            storage.append(SESSION, List.of(first, second, third));
            assertThat(storage.lastEventId(recoveredSession)).contains(new EventId(3));
            cachedOperations.reset();

            cachedResult = storage.append(SESSION, retry);

            assertThat(cachedResult.durableThrough()).contains(new EventId(5));
            assertThat(cachedResult.newlyStored()).containsExactly(fourth, fifth);
            assertThat(Files.readAllBytes(root.resolve("session-1/00000001.cbor")))
                    .isEqualTo(encoded(first, second, third, fourth, fifth));

            cachedOperations.reset();
            Path active = root.resolve("session-1/00000001.cbor");
            byte[] beforeDuplicates = Files.readAllBytes(active);

            JournalAppendResult duplicatesOnly = storage.append(
                    SESSION, List.of(first, second, third));

            assertThat(duplicatesOnly.durableThrough()).contains(new EventId(5));
            assertThat(duplicatesOnly.newlyStored()).isEmpty();
            assertThat(Files.readAllBytes(active)).isEqualTo(beforeDuplicates);
            assertThat(cachedOperations.mutationCounts()).containsExactly(0, 0, 0);
        }

        CountingFileOperations recoveredOperations = new CountingFileOperations();
        try (var storage = new FileSystemSessionJournalStorage(
                root, testConfig(), recoveredOperations)) {
            assertThat(storage.lastEventId(recoveredSession)).contains(new EventId(3));
            recoveredOperations.reset();

            JournalAppendResult recoveredResult = storage.append(recoveredSession, retry);

            assertThat(recoveredResult).isEqualTo(cachedResult);
            assertThat(Files.readAllBytes(root.resolve("recovered-overlap/00000001.cbor")))
                    .isEqualTo(encoded(first));
            assertThat(Files.readAllBytes(root.resolve("recovered-overlap/00000002.cbor")))
                    .isEqualTo(encoded(second, third, fourth, fifth));
            assertThat(recoveredOperations.bytesRead(
                    root.resolve("recovered-overlap/00000001.cbor"))).isZero();
        }
    }

    @Test
    void retainsOnlyRequestedRecordsDuringDenseRetryLookup() throws Exception {
        int recordCount = 30_000;
        List<SessionEventRecord> records = new ArrayList<>(recordCount);
        for (int id = 1; id <= recordCount; id++) {
            records.add(event(id));
        }
        Path segment = root.resolve("dense-retry/00000001.cbor");
        writeSegment(
                root,
                "dense-retry",
                1,
                records.toArray(SessionEventRecord[]::new));
        SessionEventRecord requested = records.get(recordCount / 2);
        RetentionCountingFileOperations operations = new RetentionCountingFileOperations();
        SessionId sessionId = new SessionId("dense-retry");

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig(), operations)) {
            assertThat(storage.lastEventId(sessionId)).contains(new EventId(recordCount));
            operations.reset();

            JournalAppendResult result = storage.append(sessionId, List.of(requested));

            assertThat(result.durableThrough()).contains(new EventId(recordCount));
            assertThat(result.newlyStored()).isEmpty();
            assertThat(operations.bytesRead()).isEqualTo(Files.size(segment));
            assertThat(operations.retainedEventIds()).containsExactly(requested.eventId());
        }
    }

    @Test
    void rejectsConflictingRetryWithoutMutation() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        SessionEventRecord third = event(3);
        SessionEventRecord conflictingSecond = opaqueEvent(2);
        CountingFileOperations operations = new CountingFileOperations();

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig(), operations)) {
            storage.append(SESSION, List.of(first, second, third));
            Path active = root.resolve("session-1/00000001.cbor");
            byte[] expectedBytes = Files.readAllBytes(active);
            long expectedSize = Files.size(active);
            FileTime expectedModified = Files.getLastModifiedTime(active);
            operations.reset();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(
                            SESSION, List.of(conflictingSecond, event(4))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.CONFLICTING_DUPLICATE);
            assertUnchanged(
                    storage,
                    SESSION,
                    active,
                    expectedBytes,
                    expectedSize,
                    expectedModified,
                    new EventId(3),
                    operations);

            operations.reset();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(
                            SESSION, List.of(first, conflictingSecond, event(4))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.CONFLICTING_DUPLICATE);
            assertUnchanged(
                    storage,
                    SESSION,
                    active,
                    expectedBytes,
                    expectedSize,
                    expectedModified,
                    new EventId(3),
                    operations);

            for (List<SessionEventRecord> invalid : List.of(
                    List.of(event(4), event(4)),
                    List.of(event(4), event(2)),
                    List.of(event(5), event(4), event(6)))) {
                operations.reset();
                assertThatExceptionOfType(JournalStorageException.class)
                        .isThrownBy(() -> storage.append(SESSION, invalid))
                        .extracting(JournalStorageException::reason)
                        .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
                assertUnchanged(
                        storage,
                        SESSION,
                        active,
                        expectedBytes,
                        expectedSize,
                        expectedModified,
                        new EventId(3),
                        operations);
            }
        }

        SessionId gapped = new SessionId("gapped");
        CountingFileOperations gapOperations = new CountingFileOperations();
        try (var storage = new FileSystemSessionJournalStorage(root, testConfig(), gapOperations)) {
            storage.append(gapped, List.of(event(1), event(3)));
            Path active = root.resolve("gapped/00000001.cbor");
            byte[] expectedBytes = Files.readAllBytes(active);
            long expectedSize = Files.size(active);
            FileTime expectedModified = Files.getLastModifiedTime(active);
            gapOperations.reset();

            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(gapped, List.of(event(2), event(4))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.INVALID_APPEND);
            assertUnchanged(
                    storage,
                    gapped,
                    active,
                    expectedBytes,
                    expectedSize,
                    expectedModified,
                    new EventId(3),
                    gapOperations);
        }
    }

    @Test
    void ordersEventIdsAsUnsignedValues() throws Exception {
        SessionId boundary = new SessionId("unsigned-boundary");
        SessionEventRecord signedMaximum = event(Long.MAX_VALUE);
        SessionEventRecord signedMinimum = event(Long.MIN_VALUE);
        SessionEventRecord unsignedMaximum = event(-1L);
        writeSegment(root, boundary.value(), 1, signedMaximum);
        writeSegment(root, boundary.value(), 2, signedMinimum);

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            JournalAppendResult result = storage.append(
                    boundary, List.of(signedMaximum, signedMinimum, unsignedMaximum));

            assertThat(result.durableThrough()).contains(new EventId(-1L));
            assertThat(result.newlyStored()).containsExactly(unsignedMaximum);
            assertThat(Files.readAllBytes(root.resolve("unsigned-boundary/00000001.cbor")))
                    .isEqualTo(encoded(signedMaximum));
            assertThat(Files.readAllBytes(root.resolve("unsigned-boundary/00000002.cbor")))
                    .isEqualTo(encoded(signedMinimum, unsignedMaximum));

            SessionId timeDerived = new SessionId("time-derived");
            SessionEventRecord earlier = event(1_700_000_000_000_000L);
            SessionEventRecord later = event(1_700_000_123_456_789L);
            JournalAppendResult nonConsecutive = storage.append(
                    timeDerived, List.of(earlier, later));

            assertThat(nonConsecutive.durableThrough()).contains(later.eventId());
            assertThat(nonConsecutive.newlyStored()).containsExactly(earlier, later);
        }
    }

    @Test
    void exclusivelyLeasesNormalizedRootUntilClose() throws Exception {
        Path leasedRoot = root.resolve("leased");
        FileSystemSessionJournalStorage first = new FileSystemSessionJournalStorage(
                leasedRoot, testConfig());
        FileSystemSessionJournalStorage second = new FileSystemSessionJournalStorage(
                leasedRoot.resolve("nested").resolve(".."), testConfig());
        FileSystemSessionJournalStorage differentRoot = new FileSystemSessionJournalStorage(
                root.resolve("independent"), testConfig());
        try {
            assertThat(first.lastEventId(SESSION)).isEmpty();
            assertThat(differentRoot.lastEventId(SESSION)).isEmpty();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> second.lastEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            first.close();

            assertThat(second.lastEventId(SESSION)).isEmpty();
        } finally {
            first.close();
            second.close();
            differentRoot.close();
        }
    }

    @Test
    void rejectsCaseAliasOfExistingSessionBeforeReadingStoredRecords() throws Exception {
        SessionId storedSession = new SessionId("Session-A");
        SessionId alias = new SessionId("session-a");
        SessionEventRecord stored = event(1);
        try (var writer = new FileSystemSessionJournalStorage(
                root, testConfig(), new DurableFileOperations())) {
            writer.append(storedSession, List.of(stored));
        }
        Path segment = root.resolve(storedSession.value()).resolve("00000001.cbor");
        CountingFileOperations operations = new CountingFileOperations();

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig(), operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.readAfter(alias, Optional.empty()))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(operations.bytesRead(segment)).isZero();

            assertThat(storage.readAfter(storedSession, Optional.empty()).records())
                    .containsExactly(stored);
        }
    }

    @Test
    void exclusivelyLeasesCaseFoldedRootAliasUntilClose() throws Exception {
        Path firstRoot = Files.createDirectories(root.resolve("Case-Journals"));
        Path aliasRoot = root.resolve("case-journals");
        FileSystemSessionJournalStorage first = new FileSystemSessionJournalStorage(
                firstRoot, testConfig());
        FileSystemSessionJournalStorage alias = new FileSystemSessionJournalStorage(
                aliasRoot, testConfig());
        try {
            assertThat(first.lastEventId(SESSION)).isEmpty();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> alias.lastEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            first.close();

            assertThat(alias.lastEventId(SESSION)).isEmpty();
        } finally {
            first.close();
            alias.close();
        }
    }

    @Test
    void exclusivelyLeasesRootAcrossSymlinkedAncestorAlias() throws Exception {
        Path realParent = Files.createDirectories(root.resolve("real-parent"));
        Path aliasParent = root.resolve("alias-parent");
        Files.createSymbolicLink(aliasParent, realParent);
        Path realRoot = realParent.resolve("journals");
        Path aliasRoot = aliasParent.resolve("journals");
        FileSystemSessionJournalStorage first = new FileSystemSessionJournalStorage(
                realRoot, testConfig());
        FileSystemSessionJournalStorage alias = new FileSystemSessionJournalStorage(
                aliasRoot, testConfig());
        try {
            assertThat(first.lastEventId(SESSION)).isEmpty();
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> alias.lastEventId(SESSION))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            first.close();

            assertThat(alias.lastEventId(SESSION)).isEmpty();
        } finally {
            first.close();
            alias.close();
        }
    }

    @Test
    void rejectsInvalidStoredLayouts() throws Exception {
        writeSegment(root, "duplicate", 1, event(1));
        writeCompressedSegment(root, "duplicate", 1, event(2));

        writeSegment(root, "gap", 4, event(4));
        writeSegment(root, "gap", 6, event(6));

        writeSegment(root, "decreasing", 1, event(3));
        writeSegment(root, "decreasing", 2, event(2));

        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        writeSegment(root, "incomplete", 1, first);
        Files.write(
                root.resolve("incomplete/00000001.cbor"),
                Arrays.copyOf(encoded(first, second), encoded(first, second).length - 1));
        writeSegment(root, "incomplete", 2, event(3));

        writeSegment(root, "retained", 7, event(7));
        writeSegment(root, "retained", 8, event(8));
        Files.write(root.resolve("retained/00000008.cbor.zst.tmp"), new byte[]{(byte) 0xff});

        Path compressed = Files.createDirectories(root.resolve("compressed"));
        Files.write(compressed.resolve("00000001.cbor.zst"), new byte[]{1, 2, 3});

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertStoredCorruption(storage, "duplicate");
            assertStoredCorruption(storage, "gap");
            assertStoredCorruption(storage, "decreasing");
            assertStoredCorruption(storage, "incomplete");
            assertStoredCorruption(storage, "compressed");

            assertThat(storage.firstEventId(new SessionId("retained"))).contains(new EventId(7));
            assertThat(storage.lastEventId(new SessionId("retained"))).contains(new EventId(8));

            JournalReadResult retained = storage.readAfter(
                    new SessionId("retained"), Optional.of(new EventId(3)));
            assertThat(retained.gap()).contains(new JournalGap(new EventId(3), new EventId(7)));
            assertThat(retained.records()).extracting(SessionEventRecord::eventId)
                    .containsExactly(new EventId(7), new EventId(8));
        }
    }

    @Test
    void rejectsSessionPathThatIsNotDirectory() throws Exception {
        Files.write(root.resolve("not-a-directory"), new byte[]{1});

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertStoredCorruption(storage, "not-a-directory");
            assertThat(storage.firstEventId(new SessionId("missing"))).isEmpty();
        }
    }

    @Test
    void rejectsUnknownSessionDirectoryEntries() throws Exception {
        writeUnknownEntry("short-name", "0000001.cbor");
        writeUnknownEntry("long-name", "000000001.cbor.zst");
        writeUnknownEntry("unknown-temp", "00000001.cbor.zst.partial");

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertStoredCorruption(storage, "short-name");
            assertStoredCorruption(storage, "long-name");
            assertStoredCorruption(storage, "unknown-temp");
        }
    }

    @Test
    void rejectsSymbolicAndNonRegularSegmentEntries() throws Exception {
        Path outsideSegment = root.resolve("outside.cbor");
        Files.write(outsideSegment, encoded(event(1)));
        Path symbolicSession = Files.createDirectories(root.resolve("symbolic-segment"));
        Files.createSymbolicLink(symbolicSession.resolve("00000001.cbor"), outsideSegment);

        Path nonRegularSession = Files.createDirectories(root.resolve("non-regular-segment"));
        Files.createDirectory(nonRegularSession.resolve("00000001.cbor"));

        try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
            assertStoredCorruption(storage, "symbolic-segment");
            assertStoredCorruption(storage, "non-regular-segment");
        }
    }

    @Test
    void preservesNearLimitRecordWithLinearAllocation() throws Exception {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxMessageBytes(1024 * 1024);
        SessionEventCodec codec = new SessionEventCodec(limits);
        byte[] payload = new byte[limits.maxMessageBytes() - 64];
        Arrays.fill(payload, (byte) 0x5a);
        SessionEventRecord expected = codec.decode(codec.encode(
                new EventId(1),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(payload))));
        writeSegment(root, SESSION.value(), 1, expected);
        com.sun.management.ThreadMXBean allocationBean = allocationBean();

        JournalReadResult result;
        long before = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        try (var storage = new FileSystemSessionJournalStorage(root, new JournalStorageConfig(limits))) {
            result = storage.readAfter(SESSION, Optional.empty());
        }
        long allocated = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;

        assertThat(result.records()).singleElement()
                .extracting(record -> record.encodedRecord().toByteArray())
                .isEqualTo(expected.encodedRecord().toByteArray());
        assertThat(allocated).isLessThan(64L * expected.encodedRecord().size());
    }

    @Test
    void rejectsSegmentReplacementAfterCatalogSnapshot() throws Exception {
        SessionEventRecord record = event(1);
        writeSegment(root, SESSION.value(), 1, record);
        Path segment = root.resolve(SESSION.value()).resolve("00000001.cbor");
        SegmentReader reader = new SegmentReader(AgentProtocolLimits.defaults());
        SegmentCatalog snapshot = reader.rebuild(segment.getParent());

        Path replacement = root.resolve("replacement.cbor");
        Files.write(replacement, record.encodedRecord().toByteArray());
        Files.move(replacement, segment, StandardCopyOption.REPLACE_EXISTING);

        assertThatExceptionOfType(JournalStorageException.class)
                .isThrownBy(() -> reader.readRecords(snapshot))
                .extracting(JournalStorageException::reason)
                .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
    }

    @Test
    void rejectsSessionDirectoryReplacementAfterCatalogSnapshot() throws Exception {
        SessionEventRecord record = event(1);
        writeSegment(root, SESSION.value(), 1, record);
        Path sessionDirectory = root.resolve(SESSION.value());
        SegmentReader reader = new SegmentReader(AgentProtocolLimits.defaults());
        SegmentCatalog snapshot = reader.rebuild(sessionDirectory);

        Files.move(sessionDirectory, root.resolve("previous-session-directory"));
        writeSegment(root, SESSION.value(), 1, record);

        assertThatExceptionOfType(JournalStorageException.class)
                .isThrownBy(() -> reader.readRecords(snapshot))
                .extracting(JournalStorageException::reason)
                .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
    }

    @Test
    void boundsDecoderBatchesForDenseSegments() throws Exception {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxMessageBytes(1024 * 1024);
        List<SessionEventRecord> expected = new ArrayList<>();
        for (int id = 1; id <= 30_000; id++) {
            expected.add(event(id));
        }
        byte[] sequence = encoded(expected.toArray(SessionEventRecord[]::new));
        SessionEventDecoder decoder = new SessionEventDecoder(limits);
        List<SessionEventRecord> actual = new ArrayList<>();
        AtomicInteger largestBatch = new AtomicInteger();

        SegmentReader.decodeIncrementally(
                new ByteArrayInputStream(sequence),
                sequence.length,
                decoder,
                limits.maxMessageBytes(),
                batch -> {
                    largestBatch.accumulateAndGet(batch.size(), Math::max);
                    actual.addAll(batch);
                });

        assertThat(decoder.pendingBytes()).isZero();
        assertThat(actual).hasSameSizeAs(expected);
        assertThat(largestBatch).hasValueLessThan(10_000);
        assertThat(actual.getFirst().encodedRecord().toByteArray())
                .isEqualTo(expected.getFirst().encodedRecord().toByteArray());
        assertThat(actual.getLast().encodedRecord().toByteArray())
                .isEqualTo(expected.getLast().encodedRecord().toByteArray());
    }

    @Test
    void stopsIncrementalDecodeAtRejectedOutcome() throws Exception {
        SessionEventRecord first = event(1);
        SessionEventRecord second = event(2);
        byte[] invalidRecord = new byte[]{(byte) 0x82, 0x01, 0x19, 0x01, 0x00};
        byte[] sequence = ByteBuffer.allocate(
                        first.encodedRecord().size() + invalidRecord.length + second.encodedRecord().size())
                .put(first.encodedRecord().toByteArray())
                .put(invalidRecord)
                .put(second.encodedRecord().toByteArray())
                .array();
        List<SessionEventRecord> accepted = new ArrayList<>();

        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> SegmentReader.decodeIncrementally(
                        new ByteArrayInputStream(sequence),
                        sequence.length,
                        new SessionEventDecoder(AgentProtocolLimits.defaults()),
                        AgentProtocolLimits.defaults().maxMessageBytes(),
                        accepted::addAll))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
        assertThat(accepted).containsExactly(first);
    }

    private static JournalStorageConfig testConfig() {
        return new JournalStorageConfig(AgentProtocolLimits.defaults());
    }

    private JournalStorageConfig segmentConfig(long targetSegmentBytes) {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults();
        return new JournalStorageConfig(
                limits,
                limits.maxCollectionEntries(),
                limits.maxMessageBytes(),
                targetSegmentBytes);
    }

    private List<String> segmentNames(SessionId sessionId) throws IOException {
        try (var entries = Files.list(root.resolve(sessionId.value()))) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertUnchanged(
            FileSystemSessionJournalStorage storage,
            SessionId sessionId,
            Path active,
            byte[] expectedBytes,
            long expectedSize,
            FileTime expectedModified,
            EventId expectedCursor,
            CountingFileOperations operations) throws Exception {
        assertThat(Files.readAllBytes(active)).isEqualTo(expectedBytes);
        assertThat(Files.size(active)).isEqualTo(expectedSize);
        assertThat(Files.getLastModifiedTime(active)).isEqualTo(expectedModified);
        assertThat(storage.lastEventId(sessionId)).contains(expectedCursor);
        assertThat(operations.mutationCounts()).containsExactly(0, 0, 0);
    }

    private void writeUnknownEntry(String sessionId, String name) throws Exception {
        Path sessionDirectory = Files.createDirectories(root.resolve(sessionId));
        Files.write(sessionDirectory.resolve(name), new byte[]{1});
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        assertThat(ManagementFactory.getThreadMXBean())
                .isInstanceOf(com.sun.management.ThreadMXBean.class);
        com.sun.management.ThreadMXBean allocationBean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(allocationBean.isThreadAllocatedMemorySupported()).isTrue();
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    private static void assertStoredCorruption(FileSystemSessionJournalStorage storage, String sessionId) {
        assertThatExceptionOfType(JournalStorageException.class)
                .isThrownBy(() -> storage.firstEventId(new SessionId(sessionId)))
                .extracting(JournalStorageException::reason)
                .isEqualTo(JournalStorageException.Reason.STORED_CORRUPTION);
    }

    private static final class CountingFileOperations extends DurableFileOperations {
        private final Map<Path, Long> bytesRead = new HashMap<>();
        private int writes;
        private int fileForces;
        private int directoryForces;

        @Override
        void contentBytesRead(Path path, int count) {
            bytesRead.merge(path, (long) count, Long::sum);
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            writes++;
            return super.write(channel, source);
        }

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

        private long bytesRead(Path path) {
            return bytesRead.getOrDefault(path, 0L);
        }

        private List<Integer> mutationCounts() {
            return List.of(writes, fileForces, directoryForces);
        }

        private void reset() {
            bytesRead.clear();
            writes = 0;
            fileForces = 0;
            directoryForces = 0;
        }
    }

    private static final class RetentionCountingFileOperations extends DurableFileOperations {
        private final List<EventId> retainedEventIds = new ArrayList<>();
        private long bytesRead;

        @Override
        void contentBytesRead(Path path, int count) {
            bytesRead += count;
        }

        @Override
        void retryLookupRecordRetained(SessionEventRecord record) {
            retainedEventIds.add(record.eventId());
        }

        private List<EventId> retainedEventIds() {
            return List.copyOf(retainedEventIds);
        }

        private long bytesRead() {
            return bytesRead;
        }

        private void reset() {
            retainedEventIds.clear();
            bytesRead = 0;
        }
    }
}
