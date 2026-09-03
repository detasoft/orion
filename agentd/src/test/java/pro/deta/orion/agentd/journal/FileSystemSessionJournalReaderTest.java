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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
