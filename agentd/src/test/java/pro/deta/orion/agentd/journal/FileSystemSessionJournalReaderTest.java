package pro.deta.orion.agentd.journal;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionEventType;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemSessionJournalReaderTest {
    private static final SessionEventCodec CODEC = new SessionEventCodec(AgentProtocolLimits.defaults());

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsExactRecordsAndRangeFromOneRawSegment() throws Exception {
        byte[] first = event(1, new byte[]{1, 2});
        byte[] second = event(2, new byte[]{3, 4});
        byte[] segment = concatenate(first, second);
        Files.write(temporaryDirectory.resolve("00000001.cbor"), segment);

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1), new EventId(2));
        assertThat(result.records()).extracting(record -> record.encodedRecord().toByteArray())
                .containsExactly(first, second);
        assertThat(result.firstAvailableEventId()).contains(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(2));
        assertThat(result.gap()).isEmpty();
        assertThat(result.issue()).isEmpty();
        assertThat(result.ignoredIncompleteTail()).isFalse();
    }

    @Test
    void readsNativeMaximumPtyOutputRecordBeyondAgentFrameLimit() throws Exception {
        int payloadBytes = AgentProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(payloadBytes + 10);
        buffer.put((byte) 0x83);
        buffer.put((byte) 0x01);
        buffer.put((byte) 0x19).putShort((short) SessionEventType.PTY_OUTPUT);
        buffer.put((byte) 0x5a).putInt(payloadBytes);
        byte[] encoded = buffer.array();
        Files.write(temporaryDirectory.resolve("00000001.cbor"), encoded);

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(encoded.length).isGreaterThan(AgentProtocolLimits.DEFAULT_MAX_FRAME_BYTES);
        assertThat(result.issue()).isEmpty();
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.eventId()).isEqualTo(new EventId(1));
            assertThat(record.eventType()).isEqualTo(SessionEventType.PTY_OUTPUT);
            assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
        });
        assertThat(result.firstAvailableEventId()).contains(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(1));
        assertThat(result.gap()).isEmpty();
        assertThat(result.ignoredIncompleteTail()).isFalse();
    }

    @Test
    void validatesPageLimitsAndKeepsPositionsOpaque() {
        assertThatThrownBy(() -> new JournalReadLimits(0, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalReadLimits(
                1,
                AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES - 1L))
                .isInstanceOf(IllegalArgumentException.class);

        JournalReadLimits limits = new JournalReadLimits(
                3,
                AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);

        assertThat(limits.maxRecords()).isEqualTo(3);
        assertThat(limits.maxEncodedBytes())
                .isEqualTo(AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);
        assertThat(Arrays.stream(JournalReadPosition.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName()))
                .containsExactly("lastEventId");
        assertThat(Arrays.stream(JournalReadPosition.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())))
                .isTrue();
    }

    @Test
    void requiresBothFileKeysBeforeReusingAPhysicalPosition() {
        Object expected = new String("same-key");
        Object actual = new String("same-key");

        assertThat(FileSystemSessionJournalReader.hasSameFileIdentity(expected, actual)).isTrue();
        assertThat(FileSystemSessionJournalReader.hasSameFileIdentity(null, actual)).isFalse();
        assertThat(FileSystemSessionJournalReader.hasSameFileIdentity(expected, null)).isFalse();
        assertThat(FileSystemSessionJournalReader.hasSameFileIdentity(null, null)).isFalse();
        assertThat(FileSystemSessionJournalReader.hasSameFileIdentity(expected, "different-key")).isFalse();
    }

    @Test
    void rejectsContradictoryPageContractsWithoutRejectingValidPrefixes() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(1, new byte[]{1}));
        JournalReadPage source = new FileSystemSessionJournalReader().readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(10));

        assertThatThrownBy(() -> new JournalReadPage(
                source.records(),
                Optional.empty(),
                Optional.empty(),
                source.nextPosition(),
                JournalReadBoundary.COMPLETE,
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emptyPageAt(JournalReadBoundary.PAGE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emptyPageAt(JournalReadBoundary.INCOMPLETE_TAIL))
                .isInstanceOf(IllegalArgumentException.class);

        JournalReadPage gapPrefix = new JournalReadPage(
                source.records(),
                source.firstAvailableEventId(),
                source.lastAvailableEventId(),
                source.nextPosition(),
                JournalReadBoundary.GAP,
                Optional.of(new JournalCursorGap(new EventId(0), new EventId(1))),
                Optional.empty());
        JournalReadPage issuePrefix = new JournalReadPage(
                source.records(),
                source.firstAvailableEventId(),
                source.lastAvailableEventId(),
                source.nextPosition(),
                JournalReadBoundary.ISSUE,
                Optional.empty(),
                Optional.of(new JournalReadIssue.Cbor(Optional.empty(), "damaged suffix")));

        assertThat(gapPrefix.records()).hasSize(1);
        assertThat(issuePrefix.records()).hasSize(1);
    }

    @Test
    void boundsPagesByRecordCountAndContinuesWithoutLoss() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2});
        byte[] third = event(3, new byte[]{3});
        Files.write(temporaryDirectory.resolve("00000001.cbor"), concatenate(first, second, third));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadLimits limits = limits(2);

        JournalReadPage firstPage = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits);
        JournalReadPosition position = firstPage.nextPosition().orElseThrow();
        JournalReadPage secondPage = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits);

        assertThat(firstPage.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1), new EventId(2));
        assertThat(firstPage.firstAvailableEventId()).contains(new EventId(1));
        assertThat(firstPage.lastAvailableEventId()).contains(new EventId(3));
        assertThat(firstPage.boundary()).isEqualTo(JournalReadBoundary.PAGE_LIMIT);
        assertThat(firstPage.gap()).isEmpty();
        assertThat(firstPage.issue()).isEmpty();
        assertThat(position.lastEventId()).contains(new EventId(2));
        assertThat(secondPage.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(3));
        assertThat(secondPage.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(secondPage.lastAvailableEventId()).contains(new EventId(3));
    }

    @Test
    void boundsPagesByEncodedBytes() throws Exception {
        int payloadBytes = AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES / 2;
        byte[] first = event(1, new byte[payloadBytes]);
        byte[] second = event(2, new byte[payloadBytes]);
        Files.write(temporaryDirectory.resolve("00000001.cbor"), concatenate(first, second));
        JournalReadLimits limits = new JournalReadLimits(
                10,
                AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);

        JournalReadPage page = new FileSystemSessionJournalReader().readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits);

        assertThat((long) first.length).isLessThan(limits.maxEncodedBytes());
        assertThat((long) first.length + second.length).isGreaterThan(limits.maxEncodedBytes());
        assertThat(page.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(page.lastAvailableEventId()).contains(new EventId(2));
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.PAGE_LIMIT);
    }

    @Test
    void keepsPageRecordsImmutableAndPreservesOpaqueRecordBytes() throws Exception {
        byte[] encoded = hex("8405197ffe44deadbeef66667574757265");
        Files.write(temporaryDirectory.resolve("00000001.cbor"), encoded);

        JournalReadPage page = new FileSystemSessionJournalReader().readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(10));

        assertThatThrownBy(() -> page.records().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        SessionEventRecord record = page.records().getFirst();
        assertThat(record.eventType()).isEqualTo(0x7ffe);
        assertThat(record.encodedPayload().toByteArray()).containsExactly(hex("44deadbeef"));
        assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
        assertThat(record.trailingFieldCount()).isOne();
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
    }

    @Test
    void keepsRawPositionAtTheStartOfAnIncompleteTail() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2, 3});
        Path active = temporaryDirectory.resolve("00000001.cbor");
        Files.write(active, concatenate(first, Arrays.copyOf(second, second.length - 1)));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();

        JournalReadPage partial = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(10));
        JournalReadPosition position = partial.nextPosition().orElseThrow();
        Files.write(
                active,
                Arrays.copyOfRange(second, second.length - 1, second.length),
                java.nio.file.StandardOpenOption.APPEND);
        JournalReadPage completed = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(partial.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(partial.boundary()).isEqualTo(JournalReadBoundary.INCOMPLETE_TAIL);
        assertThat(position.offset()).isEqualTo(first.length);
        assertThat(completed.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(completed.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(completed.nextPosition().orElseThrow().offset())
                .isEqualTo(first.length + second.length);
    }

    @Test
    void completesTheFirstRecordFromAnEmptyPhysicalPosition() throws Exception {
        byte[] first = event(1, new byte[]{1, 2});
        Path active = temporaryDirectory.resolve("00000001.cbor");
        Files.write(active, Arrays.copyOf(first, first.length - 1));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();

        JournalReadPage partial = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(10));
        JournalReadPosition position = partial.nextPosition().orElseThrow();
        Files.write(
                active,
                Arrays.copyOfRange(first, first.length - 1, first.length),
                java.nio.file.StandardOpenOption.APPEND);

        JournalReadPage completed = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(partial.records()).isEmpty();
        assertThat(partial.firstAvailableEventId()).isEmpty();
        assertThat(partial.lastAvailableEventId()).isEmpty();
        assertThat(partial.boundary()).isEqualTo(JournalReadBoundary.INCOMPLETE_TAIL);
        assertThat(position.lastEventId()).isEmpty();
        assertThat(position.offset()).isZero();
        assertThat(completed.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(completed.firstAvailableEventId()).contains(new EventId(1));
        assertThat(completed.lastAvailableEventId()).contains(new EventId(1));
        assertThat(completed.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
    }

    @Test
    void returnsExplicitGapAndIssuePageBoundaries() throws Exception {
        Path gapDirectory = Files.createDirectory(temporaryDirectory.resolve("gap"));
        Files.write(gapDirectory.resolve("00000003.cbor"), event(10, new byte[]{1}));
        JournalReadPage gap = new FileSystemSessionJournalReader().readPage(
                gapDirectory,
                Optional.of(new EventId(5)),
                Optional.empty(),
                limits(10));

        Path issueDirectory = Files.createDirectory(temporaryDirectory.resolve("issue"));
        byte[] valid = event(1, new byte[]{1});
        Files.write(issueDirectory.resolve("00000001.cbor"), concatenate(valid, new byte[]{(byte) 0xff}));
        JournalReadPage issue = new FileSystemSessionJournalReader().readPage(
                issueDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(10));

        assertThat(gap.boundary()).isEqualTo(JournalReadBoundary.GAP);
        assertThat(gap.gap()).contains(new JournalCursorGap(new EventId(5), new EventId(10)));
        assertThat(gap.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(10));
        assertThat(issue.boundary()).isEqualTo(JournalReadBoundary.ISSUE);
        assertThat(issue.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(issue.issue()).hasValueSatisfying(
                value -> assertThat(value).isInstanceOf(JournalReadIssue.Cbor.class));
    }

    @Test
    void rejectsACursorThatDoesNotMatchThePhysicalPosition() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(1, new byte[]{1}));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPosition position = reader.readPage(
                        temporaryDirectory,
                        Optional.empty(),
                        Optional.empty(),
                        limits(10))
                .nextPosition().orElseThrow();

        JournalReadPage mismatch = reader.readPage(
                temporaryDirectory,
                Optional.of(new EventId(2)),
                Optional.of(position),
                limits(10));

        assertThat(mismatch.records()).isEmpty();
        assertThat(mismatch.boundary()).isEqualTo(JournalReadBoundary.ISSUE);
        assertThat(mismatch.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Position.class));
        assertThat(mismatch.nextPosition()).isEmpty();
    }

    @Test
    void reusesAnActiveRawOffsetWithoutInspectingTheValidatedPrefix() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2});
        Path active = temporaryDirectory.resolve("00000001.cbor");
        Files.write(active, first);
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPosition position = reader.readPage(
                        temporaryDirectory,
                        Optional.empty(),
                        Optional.empty(),
                        limits(10))
                .nextPosition().orElseThrow();
        try (RandomAccessFile file = new RandomAccessFile(active.toFile(), "rw")) {
            file.write(0xff);
            file.seek(first.length);
            file.write(second);
        }

        JournalReadPage page = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(page.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(page.firstAvailableEventId()).contains(new EventId(1));
        assertThat(page.lastAvailableEventId()).contains(new EventId(2));
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
    }

    @Test
    void continuesAcrossRawSegmentRotation() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(1, new byte[]{1}));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPosition position = reader.readPage(
                        temporaryDirectory,
                        Optional.empty(),
                        Optional.empty(),
                        limits(10))
                .nextPosition().orElseThrow();
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(2, new byte[]{2}));

        JournalReadPage page = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(page.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(page.nextPosition().orElseThrow().segmentNumber()).isEqualTo(2);
    }

    @Test
    void rejectsAFormerlyActiveEmptySegmentAfterRotation() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), new byte[0]);
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPosition position = reader.readPage(
                        temporaryDirectory,
                        Optional.empty(),
                        Optional.empty(),
                        limits(10))
                .nextPosition().orElseThrow();
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(1, new byte[]{1}));

        JournalReadPage page = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(page.records()).isEmpty();
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.ISSUE);
        assertThat(page.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Layout.class));
    }

    @Test
    void rejectsAnEmptyClosedSegmentAfterTheResumedSegment() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(1, new byte[]{1}));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPosition position = reader.readPage(
                        temporaryDirectory,
                        Optional.empty(),
                        Optional.empty(),
                        limits(10))
                .nextPosition().orElseThrow();
        Files.write(temporaryDirectory.resolve("00000002.cbor"), new byte[0]);
        Files.write(temporaryDirectory.resolve("00000003.cbor"), event(3, new byte[]{3}));

        JournalReadPage page = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(page.records()).isEmpty();
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.ISSUE);
        assertThat(page.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Layout.class));
    }

    @Test
    void reportsCborForAFormerlyActivePartialSegmentAfterRotation() throws Exception {
        byte[] first = event(1, new byte[]{1, 2});
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                Arrays.copyOf(first, first.length - 1));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPosition position = reader.readPage(
                        temporaryDirectory,
                        Optional.empty(),
                        Optional.empty(),
                        limits(10))
                .nextPosition().orElseThrow();
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(2, new byte[]{2}));

        JournalReadPage page = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(page.records()).isEmpty();
        assertThat(page.boundary()).isEqualTo(JournalReadBoundary.ISSUE);
        assertThat(page.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Cbor.class));
    }

    @Test
    void fallsBackByEventIdAfterRawSegmentCompression() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2});
        Path raw = temporaryDirectory.resolve("00000001.cbor");
        Files.write(raw, concatenate(first, second));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPage firstPage = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(1));
        JournalReadPosition position = firstPage.nextPosition().orElseThrow();
        Files.write(
                temporaryDirectory.resolve("00000001.cbor.zst"),
                com.github.luben.zstd.Zstd.compress(Files.readAllBytes(raw)));
        Files.delete(raw);

        JournalReadPage recovered = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(recovered.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(recovered.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(recovered.issue()).isEmpty();
    }

    @Test
    void retriesByCursorWhenThePositionedPathIsReplacedAfterResumeSelection() throws Exception {
        Path active = temporaryDirectory.resolve("00000001.cbor");
        Files.write(active, concatenate(event(1, new byte[128]), event(2, new byte[128])));
        JournalReadPage firstPage = new FileSystemSessionJournalReader().readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(1));
        JournalReadPosition position = firstPage.nextPosition().orElseThrow();
        byte[] replacement = concatenate(event(1, new byte[]{1}), event(3, new byte[]{3}));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader(
                path -> replace(path, replacement));

        JournalReadPage recovered = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(recovered.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(3));
        assertThat(recovered.firstAvailableEventId()).contains(new EventId(1));
        assertThat(recovered.lastAvailableEventId()).contains(new EventId(3));
        assertThat(recovered.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(recovered.issue()).isEmpty();
    }

    @Test
    void retriesByCursorWhenThePositionedFileIsTruncatedAfterResumeSelection() throws Exception {
        Path active = temporaryDirectory.resolve("00000001.cbor");
        Files.write(active, concatenate(event(1, new byte[128]), event(2, new byte[128])));
        JournalReadPage firstPage = new FileSystemSessionJournalReader().readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(1));
        JournalReadPosition position = firstPage.nextPosition().orElseThrow();
        byte[] replacement = concatenate(event(1, new byte[]{1}), event(3, new byte[]{3}));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader(
                path -> truncate(path, replacement));

        JournalReadPage recovered = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(recovered.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(3));
        assertThat(recovered.firstAvailableEventId()).contains(new EventId(1));
        assertThat(recovered.lastAvailableEventId()).contains(new EventId(3));
        assertThat(recovered.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(recovered.issue()).isEmpty();
    }

    @Test
    void resumesCompressedSegmentsByDecodedOffset() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2});
        Files.write(
                temporaryDirectory.resolve("00000001.cbor.zst"),
                com.github.luben.zstd.Zstd.compress(concatenate(first, second)));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();

        JournalReadPage firstPage = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(1));
        JournalReadPosition position = firstPage.nextPosition().orElseThrow();
        JournalReadPage secondPage = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(position.compressed()).isTrue();
        assertThat(position.offset()).isEqualTo(first.length);
        assertThat(secondPage.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(secondPage.boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
        assertThat(secondPage.firstAvailableEventId()).contains(new EventId(1));
        assertThat(secondPage.lastAvailableEventId()).contains(new EventId(2));
    }

    @Test
    void fallsBackAndReportsGapAfterPositionedSegmentRetention() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(1, new byte[]{1}));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(2, new byte[]{2}));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadPage firstPage = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits(1));
        JournalReadPosition position = firstPage.nextPosition().orElseThrow();
        Files.delete(temporaryDirectory.resolve("00000001.cbor"));

        JournalReadPage recovered = reader.readPage(
                temporaryDirectory,
                position.lastEventId(),
                Optional.of(position),
                limits(10));

        assertThat(recovered.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(recovered.boundary()).isEqualTo(JournalReadBoundary.GAP);
        assertThat(recovered.gap()).contains(new JournalCursorGap(new EventId(1), new EventId(2)));
        assertThat(recovered.firstAvailableEventId()).contains(new EventId(2));
        assertThat(recovered.lastAvailableEventId()).contains(new EventId(2));
    }

    @Test
    void reportsRetentionGapAndReturnsAvailableRecords() throws Exception {
        Files.write(
                temporaryDirectory.resolve("00000004.cbor"),
                concatenate(event(10, new byte[]{1}), event(20, new byte[]{2})));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(new EventId(5)));

        assertThat(result.gap()).contains(new JournalCursorGap(new EventId(5), new EventId(10)));
        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(10), new EventId(20));
        assertThat(result.firstAvailableEventId()).contains(new EventId(10));
        assertThat(result.lastAvailableEventId()).contains(new EventId(20));
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void rejectsAHoleInRetainedSegmentNumbers() throws Exception {
        Files.write(temporaryDirectory.resolve("00000003.cbor"), event(10, new byte[]{1}));
        Files.write(temporaryDirectory.resolve("00000005.cbor"), event(20, new byte[]{2}));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).isEmpty();
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Layout.class));
    }

    @Test
    void seeksPastAnOlderSegmentCoveredByTheCursor() throws Exception {
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                concatenate(event(1, new byte[]{1}), new byte[]{(byte) 0xff}));
        Files.write(
                temporaryDirectory.resolve("00000002.cbor"),
                concatenate(event(100, new byte[]{2}), event(101, new byte[]{3})));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(new EventId(100)));

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(101));
        assertThat(result.firstAvailableEventId()).contains(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(101));
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void readsRotatedSegmentsAtAndAfterCursorPositions() throws Exception {
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                concatenate(event(1, new byte[]{1}), event(2, new byte[]{2})));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(5, new byte[]{5}));

        JournalReadResult atRecord = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(new EventId(2)));
        JournalReadResult afterTail = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(new EventId(5)));

        assertThat(atRecord.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(5));
        assertThat(atRecord.firstAvailableEventId()).contains(new EventId(1));
        assertThat(atRecord.lastAvailableEventId()).contains(new EventId(5));
        assertThat(afterTail.records()).isEmpty();
        assertThat(afterTail.firstAvailableEventId()).contains(new EventId(1));
        assertThat(afterTail.lastAvailableEventId()).contains(new EventId(5));
    }

    @Test
    void comparesEventIdsAsUnsignedValues() throws Exception {
        EventId signedMaximum = new EventId(Long.MAX_VALUE);
        EventId unsignedHigh = new EventId(Long.MIN_VALUE);
        EventId unsignedMaximum = new EventId(-1);
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(signedMaximum, new byte[]{1}));
        Files.write(
                temporaryDirectory.resolve("00000002.cbor"),
                concatenate(event(unsignedHigh, new byte[]{2}), event(unsignedMaximum, new byte[]{3})));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(signedMaximum));

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(unsignedHigh, unsignedMaximum);
        assertThat(result.lastAvailableEventId()).contains(unsignedMaximum);
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void rejectsDescendingIdsAcrossRotation() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(10, new byte[]{1}));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(9, new byte[]{2}));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).isEmpty();
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.EventOrder.class));
    }

    @Test
    void rejectsZeroAndDuplicateEventIds() throws Exception {
        Path zeroDirectory = Files.createDirectory(temporaryDirectory.resolve("zero"));
        Files.write(zeroDirectory.resolve("00000001.cbor"), hex("830019010040"));
        JournalReadResult zero = new FileSystemSessionJournalReader().readAfter(
                zeroDirectory,
                Optional.empty());

        Path duplicateDirectory = Files.createDirectory(temporaryDirectory.resolve("duplicate"));
        byte[] first = event(1, new byte[]{1});
        Files.write(duplicateDirectory.resolve("00000001.cbor"), concatenate(first, first));
        JournalReadResult duplicate = new FileSystemSessionJournalReader().readAfter(
                duplicateDirectory,
                Optional.empty());

        assertThat(zero.records()).isEmpty();
        assertThat(zero.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.EventOrder.class));
        assertThat(duplicate.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(duplicate.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.EventOrder.class));
    }

    @Test
    void returnsAnEmptyRangeForAnEmptyJournal() {
        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).isEmpty();
        assertThat(result.firstAvailableEventId()).isEmpty();
        assertThat(result.lastAvailableEventId()).isEmpty();
        assertThat(result.gap()).isEmpty();
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void readsAcrossCompressedAndRawSegments() throws Exception {
        Files.write(
                temporaryDirectory.resolve("00000001.cbor.zst"),
                hex("""
                        28b52ffd0458490200830119010043001bff83021901028218b41832830319010182782430303031
                        303230332d303430352d303630372d303830392d3061306230633064306530664200ff830419020181
                        003608fc31
                        """));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(5, new byte[]{5}));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(new EventId(2)));

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(3), new EventId(4), new EventId(5));
        assertThat(result.firstAvailableEventId()).contains(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(5));
        assertThat(result.gap()).isEmpty();
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void returnsTheValidPrefixBeforeDamagedCompressedData() throws Exception {
        byte[] first = event(1, new byte[]{1});
        Files.write(temporaryDirectory.resolve("00000001.cbor"), first);
        Files.write(temporaryDirectory.resolve("00000002.cbor.zst"), new byte[]{1, 2, 3, 4});

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.records().getFirst().encodedRecord().toByteArray()).containsExactly(first);
        assertThat(result.firstAvailableEventId()).contains(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(1));
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Decompression.class));
    }

    @Test
    void prefersRawSegmentDuringCompressedReplacementOverlap() throws Exception {
        byte[] raw = event(10, new byte[]{10});
        Files.write(temporaryDirectory.resolve("00000001.cbor"), raw);
        Files.write(
                temporaryDirectory.resolve("00000001.cbor.zst"),
                hex("""
                        28b52ffd0458490200830119010043001bff83021901028218b41832830319010182782430303031
                        303230332d303430352d303630372d303830392d3061306230633064306530664200ff830419020181
                        003608fc31
                        """));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(20, new byte[]{20}));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(10), new EventId(20));
        assertThat(result.records().getFirst().encodedRecord().toByteArray()).containsExactly(raw);
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void ignoresAnIncompleteItemOnlyAtTheActiveRawTail() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2, 3});
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                concatenate(first, Arrays.copyOf(second, second.length - 1)));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.firstAvailableEventId()).contains(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(1));
        assertThat(result.ignoredIncompleteTail()).isTrue();
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void treatsAnOnlyPartialActiveItemAsAnEmptyCrashTail() throws Exception {
        byte[] event = event(1, new byte[]{1, 2});
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                Arrays.copyOf(event, event.length - 1));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).isEmpty();
        assertThat(result.firstAvailableEventId()).isEmpty();
        assertThat(result.lastAvailableEventId()).isEmpty();
        assertThat(result.ignoredIncompleteTail()).isTrue();
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void rejectsAnIncompleteItemInAClosedRawSegment() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2});
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                concatenate(first, Arrays.copyOf(second, second.length - 1)));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(3, new byte[]{3}));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.lastAvailableEventId()).contains(new EventId(1));
        assertThat(result.ignoredIncompleteTail()).isFalse();
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Cbor.class));
    }

    @Test
    void rejectsAnIncompleteItemInACompressedSegment() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2});
        byte[] decoded = concatenate(first, Arrays.copyOf(second, second.length - 1));
        Files.write(
                temporaryDirectory.resolve("00000001.cbor.zst"),
                com.github.luben.zstd.Zstd.compress(decoded));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(3, new byte[]{3}));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.ignoredIncompleteTail()).isFalse();
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Cbor.class));
    }

    @Test
    void stopsAfterInvalidCborAndKeepsTheCompletePrefix() throws Exception {
        byte[] first = event(1, new byte[]{1});
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                concatenate(first, new byte[]{(byte) 0xff}, event(2, new byte[]{2})));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.records().getFirst().encodedRecord().toByteArray()).containsExactly(first);
        assertThat(result.ignoredIncompleteTail()).isFalse();
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Cbor.class));
    }

    @Test
    void distinguishesInvalidRecordFieldsFromMalformedCbor() throws Exception {
        byte[] first = event(1, new byte[]{1});
        byte[] missingPayload = hex("8202190100");
        Files.write(
                temporaryDirectory.resolve("00000001.cbor"),
                concatenate(first, missingPayload));

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Record.class));
    }

    @Test
    void preservesUnknownPayloadAndTrailingFieldsExactly() throws Exception {
        byte[] encoded = hex("8405197ffe44deadbeef66667574757265");
        Files.write(temporaryDirectory.resolve("00000001.cbor"), encoded);

        JournalReadResult result = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        SessionEventRecord record = result.records().getFirst();
        assertThat(record.eventId()).isEqualTo(new EventId(5));
        assertThat(record.eventType()).isEqualTo(0x7ffe);
        assertThat(record.encodedPayload().toByteArray()).containsExactly(hex("44deadbeef"));
        assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
        assertThat(record.trailingFieldCount()).isOne();
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void retriesWhenRetentionRemovesADiscoveredSegmentBeforeScan() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path disappearing = temporaryDirectory.resolve("00000001.cbor");
        Process makeFifo = new ProcessBuilder("mkfifo", disappearing.toString()).start();
        assertThat(makeFifo.waitFor()).isZero();
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(2, new byte[]{2}));

        JournalReadResult result;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JournalReadResult> reading = executor.submit(() ->
                    new FileSystemSessionJournalReader().readAfter(temporaryDirectory, Optional.empty()));
            try (OutputStream output = Files.newOutputStream(disappearing)) {
                output.write(event(1, new byte[]{1}));
                output.flush();
                Files.delete(disappearing);
            }
            result = reading.get(5, TimeUnit.SECONDS);
        }

        assertThat(result.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(result.firstAvailableEventId()).contains(new EventId(2));
        assertThat(result.lastAvailableEventId()).contains(new EventId(2));
        assertThat(result.issue()).isEmpty();
    }

    @Test
    void returnsAnIoIssueWhenADiscoveredSegmentBecomesUnreadableBeforeScan() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path changed = temporaryDirectory.resolve("00000001.cbor");
        Path blocker = temporaryDirectory.resolve("00000002.cbor");
        assertThat(new ProcessBuilder("mkfifo", changed.toString()).start().waitFor()).isZero();
        assertThat(new ProcessBuilder("mkfifo", blocker.toString()).start().waitFor()).isZero();

        JournalReadResult result;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JournalReadResult> reading = executor.submit(() ->
                    new FileSystemSessionJournalReader().readAfter(temporaryDirectory, Optional.empty()));
            try (OutputStream output = Files.newOutputStream(changed)) {
                output.write(event(1, new byte[]{1}));
            }
            Files.delete(changed);
            Files.createDirectory(changed);
            try (OutputStream output = Files.newOutputStream(blocker)) {
                output.write(event(2, new byte[]{2}));
            }
            result = reading.get(5, TimeUnit.SECONDS);
        }

        assertThat(result.records()).isEmpty();
        assertThat(result.firstAvailableEventId()).isEmpty();
        assertThat(result.lastAvailableEventId()).isEmpty();
        assertThat(result.issue()).hasValueSatisfying(
                issue -> assertThat(issue).isInstanceOf(JournalReadIssue.Io.class));
    }

    @Test
    void observesAConcurrentAppendOnTheNextCursorRead() throws Exception {
        Path active = temporaryDirectory.resolve("00000001.cbor");
        byte[] first = event(1, new byte[]{1});
        byte[] second = event(2, new byte[]{2, 3});
        Files.write(active, first);
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();

        JournalReadResult initial = reader.readAfter(temporaryDirectory, Optional.empty());
        Files.write(active, Arrays.copyOf(second, second.length - 1), java.nio.file.StandardOpenOption.APPEND);
        JournalReadResult partial = reader.readAfter(temporaryDirectory, Optional.of(new EventId(1)));
        Files.write(
                active,
                Arrays.copyOfRange(second, second.length - 1, second.length),
                java.nio.file.StandardOpenOption.APPEND);
        JournalReadResult completed = reader.readAfter(temporaryDirectory, Optional.of(new EventId(1)));

        assertThat(initial.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(partial.records()).isEmpty();
        assertThat(partial.ignoredIncompleteTail()).isTrue();
        assertThat(completed.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(completed.ignoredIncompleteTail()).isFalse();
        assertThat(completed.issue()).isEmpty();
    }

    @Test
    void reconstructsStateAfterRestartAndReportsRetentionAdvance() throws Exception {
        Files.write(temporaryDirectory.resolve("00000001.cbor"), event(1, new byte[]{1}));
        Files.write(temporaryDirectory.resolve("00000002.cbor"), event(2, new byte[]{2}));
        JournalReadResult beforeRestart = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());

        JournalReadResult afterRestart = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.empty());
        Files.delete(temporaryDirectory.resolve("00000001.cbor"));
        JournalReadResult afterRetention = new FileSystemSessionJournalReader().readAfter(
                temporaryDirectory,
                Optional.of(new EventId(1)));

        assertThat(afterRestart).isEqualTo(beforeRestart);
        assertThat(afterRetention.records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(2));
        assertThat(afterRetention.gap()).contains(new JournalCursorGap(new EventId(1), new EventId(2)));
        assertThat(afterRetention.firstAvailableEventId()).contains(new EventId(2));
        assertThat(afterRetention.lastAvailableEventId()).contains(new EventId(2));
    }

    private static byte[] event(long id, byte[] payload) throws Exception {
        return event(new EventId(id), payload);
    }

    private static JournalReadLimits limits(int maxRecords) {
        return new JournalReadLimits(
                maxRecords,
                AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);
    }

    private static JournalReadPage emptyPageAt(JournalReadBoundary boundary) {
        return new JournalReadPage(
                java.util.List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                boundary,
                Optional.empty(),
                Optional.empty());
    }

    private static void replace(Path path, byte[] content) {
        try {
            Files.delete(path);
            Files.write(path, content);
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void truncate(Path path, byte[] content) {
        try {
            Files.write(path, content, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] event(EventId id, byte[] payload) throws Exception {
        return CODEC.encode(
                id,
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(payload)));
    }

    private static byte[] concatenate(byte[]... items) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] item : items) {
            output.writeBytes(item);
        }
        return output.toByteArray();
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value.replaceAll("\\s", ""));
    }
}
