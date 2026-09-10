package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agentd.journal.JournalReadLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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
                sessionDirectory, terminal, () -> false);

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
                    new TerminalJournalFollower().follow(sessionDirectory, terminal, () -> false));
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
    void reportsRequiredHistoryGapsAndCompleteCorruption() throws Exception {
        Path segment = sessionDirectory.resolve("00000001.cbor");
        Files.write(segment, output(1, "first"));
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TerminalJournalFollower.Result> result = executor.submit(() ->
                    new TerminalJournalFollower().follow(sessionDirectory, terminal, () -> false));
            awaitOutput(terminal, "first");
            Path replacement = sessionDirectory.resolve("replacement");
            Files.write(replacement, concat(output(5, "lost"), exit(6, 0)));
            Files.move(replacement, segment, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(TerminalJournalFollower.Result.Failed.class);
            assertThat(((TerminalJournalFollower.Result.Failed) result.get()).detail())
                    .contains("gap", "1", "5");
        }

        Files.write(segment, new byte[]{(byte) 0xff}, StandardOpenOption.TRUNCATE_EXISTING);
        TerminalJournalFollower.Result corrupt = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> false);
        assertThat(corrupt).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(((TerminalJournalFollower.Result.Failed) corrupt).detail()).contains("journal");
    }

    @Test
    void reportsAGapBeforeHonoringAConcurrentStop() throws Exception {
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
                });

        assertThat(result).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(((TerminalJournalFollower.Result.Failed) result).detail()).contains("gap", "1", "5");
        assertThat(stopChecks).hasValue(1);
    }

    @Test
    void reportsACompleteJournalIssueBeforeHonoringStop() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), new byte[]{(byte) 0xff});

        TerminalJournalFollower.Result result = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), () -> true);

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
                sessionDirectory, failedOutput, () -> false);

        assertThat(failed).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        assertThat(((TerminalJournalFollower.Result.Failed) failed).detail()).contains("terminal closed");

        AtomicBoolean detached = new AtomicBoolean(true);
        TerminalJournalFollower.Result stopped = new TerminalJournalFollower().follow(
                sessionDirectory, new ByteArrayOutputStream(), detached::get);
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
                sessionDirectory, terminal, () -> true);

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
                sessionDirectory, failedOutput, () -> false);

        assertThat(outputFailure).isInstanceOf(TerminalJournalFollower.Result.Failed.class);
        String outputDetail = ((TerminalJournalFollower.Result.Failed) outputFailure).detail();
        assertBoundedUtf8(outputDetail);
        assertThat(outputDetail).startsWith("journal failure: failure-");
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

    private static byte[] output(long eventId, String value) throws Exception {
        return codec().encode(new EventId(eventId),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(value.getBytes())));
    }

    private static byte[] exit(long eventId, int code) throws Exception {
        return codec().encode(new EventId(eventId), new SessionEventPayload.ProcessExited(code));
    }

    private static byte[] unknown(long eventId) throws Exception {
        return codec().encodeOpaque(new EventId(eventId), 0x7fff,
                ProtocolBytes.copyOf(new byte[]{(byte) 0xf6}), java.util.List.of());
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
