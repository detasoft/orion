package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventType;
import pro.deta.orion.agentd.journal.JournalReadLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalJournalFollowerTest {
    @TempDir
    Path sessionDirectory;

    @Test
    void replaysPagedOutputIgnoresUnknownRecordsAndStopsAfterExit() throws Exception {
        ByteArrayOutputStream journal = new ByteArrayOutputStream();
        journal.write(output(1, "one"));
        journal.write(unknown(2));
        journal.write(output(3, "two"));
        journal.write(exit(4, 7));
        Files.write(sessionDirectory.resolve("00000001.cbor"), journal.toByteArray());
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        TerminalJournalFollower follower = new TerminalJournalFollower(
                new JournalReadLimits(2, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));

        TerminalJournalFollower.Result result = follower.follow(
                sessionDirectory, terminal, () -> false, ignored -> {});

        assertThat(result).isEqualTo(new TerminalJournalFollower.Result.Exited(7));
        assertThat(terminal.toString()).isEqualTo("onetwo");
    }

    @Test
    void waitsAtAnIncompleteTailAndResumesAfterFileReplacementWithoutDuplicateOutput() throws Exception {
        byte[] first = output(1, "first");
        byte[] second = output(2, "second");
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(
                first, Arrays.copyOf(second, second.length / 2)));
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TerminalJournalFollower.Result> result = executor.submit(() ->
                    new TerminalJournalFollower().follow(
                            sessionDirectory, terminal, () -> false, ignored -> {}));
            awaitOutput(terminal, "first");
            Path replacement = sessionDirectory.resolve("replacement");
            Files.write(replacement, concat(first, second, exit(3, 0)));
            Files.move(replacement, sessionDirectory.resolve("00000001.cbor"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            assertThat(result.get(5, TimeUnit.SECONDS))
                    .isEqualTo(new TerminalJournalFollower.Result.Exited(0));
        }
        assertThat(terminal.toString()).isEqualTo("firstsecond");
    }

    @Test
    void resumesAcrossSpacedEventIdsAndReportsCompleteCorruption() throws Exception {
        Path segment = sessionDirectory.resolve("00000001.cbor");
        Files.write(segment, output(1, "first"));
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TerminalJournalFollower.Result> result = executor.submit(() ->
                    new TerminalJournalFollower().follow(
                            sessionDirectory, terminal, () -> false, ignored -> {}));
            awaitOutput(terminal, "first");
            Path replacement = sessionDirectory.resolve("replacement");
            Files.write(replacement, concat(output(5, "lost"), exit(6, 0)));
            Files.move(replacement, segment, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            assertThat(result.get(5, TimeUnit.SECONDS))
                    .isEqualTo(new TerminalJournalFollower.Result.Exited(0));
            assertThat(terminal.toString()).isEqualTo("firstlost");
        }

        Files.write(segment, new byte[]{(byte) 0xff}, StandardOpenOption.TRUNCATE_EXISTING);
        TerminalJournalFollower.Result corrupt = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> false, ignored -> {});
        assertThat(corrupt).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(((TerminalJournalFollower.Result.Failed) corrupt).detail()).contains("journal");
    }

    @Test
    void honorsAConcurrentStopAfterResumingRetainedRecords() throws Exception {
        Path segment = sessionDirectory.resolve("00000001.cbor");
        Files.write(segment, concat(output(1, "first"), output(2, "second")));
        TerminalJournalFollower follower = new TerminalJournalFollower(
                new JournalReadLimits(1, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));
        AtomicInteger stopChecks = new AtomicInteger();

        TerminalJournalFollower.Result result = follower.follow(
                sessionDirectory,
                new ByteArrayOutputStream(),
                () -> {
                    if (stopChecks.incrementAndGet() == 1) {
                        try {
                            Path replacement = sessionDirectory.resolve("replacement");
                            Files.write(replacement, concat(output(5, "lost"), exit(6, 0)));
                            Files.move(replacement, segment, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return false;
                    }
                    return true;
                }, ignored -> {});

        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Detached.class);
        assertThat(stopChecks).hasValue(2);
    }

    @Test
    void reportsACompleteJournalIssueBeforeHonoringStop() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), new byte[]{(byte) 0xff});

        TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> true, ignored -> {});

        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(((TerminalJournalFollower.Result.Failed) result).detail()).contains("journal");
    }

    @Test
    void reportsOutputFailureAndStopsWhenDetached() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), output(1, "data"));
        OutputStream failedOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("terminal closed");
            }
        };

        TerminalJournalFollower.Result failed = new TerminalJournalFollower().follow(
                sessionDirectory, failedOutput, () -> false, ignored -> {});

        assertThat(failed).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(((TerminalJournalFollower.Result.Failed) failed).detail()).contains("terminal closed");

        AtomicBoolean detached = new AtomicBoolean(true);
        TerminalJournalFollower.Result stopped = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), detached::get, ignored -> {});
        assertThat(stopped).isEqualTo(new TerminalJournalFollower.Result.Detached());
    }

    @Test
    void stopsAtTheFirstPageBoundaryWithoutChasingRepeatedPageLimits() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(
                output(1, "one"), output(2, "two"), output(3, "three")));
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        TerminalJournalFollower follower = new TerminalJournalFollower(
                new JournalReadLimits(1, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));

        TerminalJournalFollower.Result result = follower.follow(
                sessionDirectory, terminal, () -> true, ignored -> {});

        assertThat(result).isEqualTo(new TerminalJournalFollower.Result.Detached());
        assertThat(terminal.toString()).isEqualTo("one");
    }

    @Test
    void inspectsOnlyOnePageBeforeReportingAnActiveSession() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(
                output(1, "one"), output(2, "two"), exit(3, 7)));
        TerminalJournalFollower follower = new TerminalJournalFollower(
                new JournalReadLimits(1, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));

        TerminalJournalFollower.Inspection inspection = follower.inspect(sessionDirectory);

        assertThat(inspection).isEqualTo(new TerminalJournalFollower.Inspection.Active());
    }

    @Test
    void boundsOutputFailureDetailsAsUtf8() throws Exception {
        String oversized = "failure-🚀".repeat(200);
        Files.write(sessionDirectory.resolve("00000001.cbor"), output(1, "data"));
        OutputStream failedOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException(oversized);
            }
        };
        TerminalJournalFollower.Result outputFailure = new TerminalJournalFollower().follow(
                sessionDirectory, failedOutput, () -> false, ignored -> {});

        assertThat(outputFailure).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        String outputDetail = ((TerminalJournalFollower.Result.Failed) outputFailure).detail();
        assertBoundedUtf8(outputDetail);
        assertThat(outputDetail).startsWith("journal failure: failure-");
    }

    @Test
    void reportsInvalidTypedPtyValuesDuringInitialInspection() throws Exception {
        for (String hex : new String[]{"8301190101826040", "830119010282001818", "830119010282185000"}) {
            Files.write(sessionDirectory.resolve("00000001.cbor"), java.util.HexFormat.of().parseHex(hex));
            TerminalJournalFollower follower = new TerminalJournalFollower();
            for (TerminalJournalFollower.Inspection inspection : new TerminalJournalFollower.Inspection[]{
                    follower.inspect(sessionDirectory), follower.inspectUntilTail(sessionDirectory)}) {
                assertThat(inspection).isInstanceOf(TerminalJournalFollower.Inspection.Failed.class);
                String detail = ((TerminalJournalFollower.Inspection.Failed) inspection).detail();
                assertThat(detail).startsWith("journal failure: ").doesNotContain("IllegalArgumentException");
                assertBoundedUtf8(detail);
            }
        }
    }

    @Test
    void boundsJournalIssueDetailsAsUtf8() throws Exception {
        String component = "failure-🚀".repeat(8);
        Path parent = sessionDirectory;
        for (int index = 0; index < 6; index++) {
            parent = Files.createDirectory(parent.resolve(component));
        }
        Path missing = parent.resolve("journal-secret-tail");

        TerminalJournalFollower.Inspection inspection = new TerminalJournalFollower().inspect(missing);

        assertThat(inspection).isInstanceOf(TerminalJournalFollower.Inspection.Failed.class);
        String detail = ((TerminalJournalFollower.Inspection.Failed) inspection).detail();
        assertBoundedUtf8(detail);
        assertThat(detail).startsWith("journal failure: ");
        assertThat(detail).doesNotContain("journal-secret-tail");
    }

    @Test
    void reportsPrintStreamFailureInsteadOfTreatingItAsDeliveredOutput() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(output(1, "data"), exit(2, 0)));
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("closed terminal");
            }
        };

        TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                sessionDirectory, new PrintStream(broken), () -> false, ignored -> {});

        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
    }

    @Test
    void validatesTheRestOfThePageAfterProcessExit() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(exit(1, 0), new byte[]{(byte) 0xff}));

        TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> false, ignored -> {});

        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
    }

    @Test
    void acknowledgesWholeFlushedPagesIncludingUnknownRecordsAndUnsignedEventIds() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(
                output(1, "one"), unknown(Long.MAX_VALUE), output(Long.MIN_VALUE, "two"),
                exit(Long.MIN_VALUE + 7, 0), unknown(Long.MIN_VALUE + 8)));
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        List<EventId> acknowledged = new ArrayList<>();
        TerminalJournalFollower follower = new TerminalJournalFollower(
                new JournalReadLimits(2, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));

        TerminalJournalFollower.Result result = follower.follow(sessionDirectory, terminal, () -> false, id -> {
            assertThat(terminal.toString()).startsWith("one");
            acknowledged.add(id);
        });

        assertThat(result).isEqualTo(new TerminalJournalFollower.Result.Exited(0));
        assertThat(acknowledged).containsExactly(new EventId(Long.MAX_VALUE), new EventId(Long.MIN_VALUE + 7));
        assertThat(terminal.toString()).isEqualTo("onetwo");
    }

    @Test
    void waitsForTheIncompletePageToFinishBeforeAcknowledgingItsDeliveredPrefix() throws Exception {
        byte[] second = output(9, "second");
        Path segment = sessionDirectory.resolve("00000001.cbor");
        Files.write(segment, concat(output(1, "first"), Arrays.copyOf(second, second.length / 2)));
        List<EventId> acknowledged = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TerminalJournalFollower.Result> result = executor.submit(
                    () -> new TerminalJournalFollower().follow(
                    sessionDirectory, terminal, () -> false, acknowledged::add));
            awaitOutput(terminal, "first");
            Thread.sleep(250);
            assertThat(acknowledged).isEmpty();
            Files.write(segment, concat(Arrays.copyOfRange(second, second.length / 2, second.length), exit(10, 0)),
                    StandardOpenOption.APPEND);
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(new TerminalJournalFollower.Result.Exited(0));
        }
        assertThat(terminal.toString()).isEqualTo("firstsecond");
        assertThat(acknowledged).containsExactly(new EventId(10));
    }

    @Test
    void neverAcknowledgesCorruptOrMissingPagesEvenAfterAnExitRecord() throws Exception {
        Path segment = sessionDirectory.resolve("00000001.cbor");
        List<EventId> acknowledged = new ArrayList<>();
        byte[] malformedPayload = codec().encodeOpaque(new EventId(3),
                SessionEventType.PTY_OUTPUT,
                ProtocolBytes.copyOf(new byte[]{(byte) 0xf6}), List.of());
        for (byte[] bad : List.of(new byte[]{(byte) 0xff}, malformedPayload)) {
            Files.write(segment, concat(output(1, "first"), exit(2, 0), bad));
            TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                    sessionDirectory, new ByteArrayOutputStream(), () -> false, acknowledged::add);
            assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
            assertThat(acknowledged).isEmpty();
        }
        Files.write(segment, output(1, "first"));
        Files.write(sessionDirectory.resolve("00000003.cbor"), exit(4, 0));
        TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> false, acknowledged::add);
        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(acknowledged).isEmpty();
    }

    @Test
    void keepsAnEarlierSafePageAcknowledgedWhenTheFollowingPageIsCorrupt() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(
                output(1, "first"), output(2, "second"), new byte[]{(byte) 0xff}));
        List<EventId> acknowledged = new ArrayList<>();
        TerminalJournalFollower follower = new TerminalJournalFollower(
                new JournalReadLimits(1, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));

        TerminalJournalFollower.Result result = follower.follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> false, acknowledged::add);

        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(acknowledged).containsExactly(new EventId(1), new EventId(2));
    }

    @Test
    void neverAcknowledgesFailedFlushOrAConcurrentDetach() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), concat(output(1, "first"), exit(2, 0)));
        List<EventId> acknowledged = new ArrayList<>();
        OutputStream failedFlush = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw new IOException("flush failed");
            }
        };
        for (OutputStream output : List.of(failedFlush, new PrintStream(failedFlush))) {
            TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                    sessionDirectory, output, () -> false, acknowledged::add);
            assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
            assertThat(acknowledged).isEmpty();
        }
        AtomicBoolean stopped = new AtomicBoolean();
        OutputStream detaching = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                stopped.set(true);
            }
        };
        new TerminalJournalFollower().follow(sessionDirectory, detaching, stopped::get, acknowledged::add);
        assertThat(acknowledged).isEmpty();
    }

    @Test
    void doesNotAcknowledgeUndeliveredOutputAfterExitOrAnIncompleteExitPage() throws Exception {
        Path segment = sessionDirectory.resolve("00000001.cbor");
        for (byte[] tail : List.of(output(3, "after"), new byte[]{(byte) 0x83})) {
            Files.write(segment, concat(output(1, "before"), exit(2, 0), tail));
            List<EventId> acknowledged = new ArrayList<>();
            ByteArrayOutputStream terminal = new ByteArrayOutputStream();
            TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                    sessionDirectory, terminal, () -> false, acknowledged::add);
            assertThat(result).isEqualTo(new TerminalJournalFollower.Result.Exited(0));
            assertThat(terminal.toString()).isEqualTo("before");
            assertThat(acknowledged).isEmpty();
        }
    }

    @Test
    void acknowledgesDeliveredPrefixWhenAnIncompleteSuffixIsRemovedWithoutNewRecords() throws Exception {
        Path segment = sessionDirectory.resolve("00000001.cbor");
        byte[] first = output(1, "first");
        Files.write(segment, concat(first, new byte[]{(byte) 0x83}));
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        List<EventId> acknowledged = new CopyOnWriteArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TerminalJournalFollower.Result> result = executor.submit(
                    () -> new TerminalJournalFollower().follow(
                    sessionDirectory, terminal, stopped::get, id -> {
                        acknowledged.add(id);
                        stopped.set(true);
                    }));
            awaitOutput(terminal, "first");
            assertThat(acknowledged).isEmpty();
            Files.write(segment, first, StandardOpenOption.TRUNCATE_EXISTING);
            assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(TerminalJournalFollower.Result.Detached.class);
        }
        assertThat(terminal.toString()).isEqualTo("first");
        assertThat(acknowledged).containsExactly(new EventId(1));
    }

    private static byte[] output(long eventId, String value) throws Exception {
        return codec().encode(new EventId(eventId),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(value.getBytes())));
    }

    private static byte[] exit(long eventId, int code) throws Exception {
        return codec().encode(new EventId(eventId), new SessionEventPayload.ProcessExited(code));
    }

    private static byte[] unknown(long eventId) throws Exception {
        return codec().encodeOpaque(new EventId(eventId), 0x7fff,
                ProtocolBytes.copyOf(new byte[]{(byte) 0xf6}), List.of());
    }

    private static SessionEventCodec codec() {
        return new SessionEventCodec(AgentProtocolLimits.journalDefaults());
    }

    private static byte[] concat(byte[]... values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value);
        }
        return output.toByteArray();
    }

    private static void awaitOutput(ByteArrayOutputStream output, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output.toString().contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(output.toString()).contains(expected);
    }

    private static void assertBoundedUtf8(String value) {
        assertThat(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(512);
        assertThat(value).doesNotEndWith("�");
    }
}
