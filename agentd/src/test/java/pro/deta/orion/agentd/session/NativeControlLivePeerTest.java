package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agentd.journal.FileSystemSessionJournalReader;
import pro.deta.orion.agentd.journal.JournalReadLimits;
import pro.deta.orion.agentd.journal.JournalReadPage;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class NativeControlLivePeerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ProtocolBytes ENVELOPE =
            ProtocolBytes.copyOf(new byte[]{(byte) 0x84, 1, 2, 3, 0x66, 'f', 'u', 't', 'u', 'r', 'e'});
    private static final JournalReadLimits READ_LIMITS =
            new JournalReadLimits(100, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);

    @TempDir
    Path temporaryDirectory;

    @Test
    void exchangesEstablishedOperationsWithTheRealSessionHost() throws Exception {
        Path executable = extractSessionHost();
        Path sessionDirectory = Files.createDirectory(temporaryDirectory.resolve("session"));
        Path log = temporaryDirectory.resolve("session-host.log");
        Process host = startSessionHost(executable, "java-live-peer", sessionDirectory, log);
        try {
            ControlEndpoint endpoint = new ControlEndpoint(
                    ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                    "control.sock",
                    sessionDirectory.resolve("control.sock"));
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));
            awaitStatus(client, endpoint, host, log);

            assertReceived(client.send(endpoint, new ControlCommand.Input(
                    1,
                    ENVELOPE,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    ProtocolBytes.copyOf(new byte[]{'o', 'k', '\n'}))), 1);
            assertReceived(client.send(endpoint, new ControlCommand.Resize(2, ENVELOPE, 100, 30)), 2);
            assertReceived(client.send(endpoint, new ControlCommand.Signal(
                    3, ENVELOPE, AgentMessage.SignalKind.PLATFORM, continueSignal())), 3);

            JournalReadPage effects = awaitCommandResults(sessionDirectory, List.of(1L, 2L, 3L));
            long acknowledgedEventId = effects.records().getLast().eventId().value();
            long acknowledgementSequence = Long.MIN_VALUE;
            assertReceived(client.send(endpoint, new ControlCommand.AckJournal(
                    acknowledgementSequence, ENVELOPE, acknowledgedEventId)), acknowledgementSequence);
            awaitCommandResults(sessionDirectory, List.of(acknowledgementSequence));

            long terminateSequence = Long.MIN_VALUE + 1;
            assertReceived(client.send(endpoint, new ControlCommand.Terminate(
                    terminateSequence, ENVELOPE, AgentMessage.TerminationMode.FORCE)), terminateSequence);
            assertThat(host.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(host.exitValue()).as(Files.readString(log)).isZero();

            JournalReadPage completed = awaitCommandResults(
                    sessionDirectory, List.of(1L, 2L, 3L, acknowledgementSequence, terminateSequence));
            assertThat(completed.issue()).isEmpty();
            assertSignal(completed.records(), 3, 9);
            for (SessionEventRecord record : completed.records()) {
                if (record.eventType() == 0x0002) {
                    assertThat(record.encodedPayload().toByteArray())
                            .containsSubsequence(ENVELOPE.toByteArray());
                }
            }
        } finally {
            if (host.isAlive()) {
                host.destroyForcibly();
                host.waitFor();
            }
        }
    }

    @Test
    void terminatesGracefullyWithTheRealSessionHost() throws Exception {
        Path executable = extractSessionHost();
        Path sessionDirectory = Files.createDirectory(temporaryDirectory.resolve("session"));
        Path log = temporaryDirectory.resolve("graceful-session-host.log");
        Process host = startSessionHost(executable, "java-graceful-terminate", sessionDirectory, log);
        try {
            ControlEndpoint endpoint = new ControlEndpoint(
                    ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                    "control.sock",
                    sessionDirectory.resolve("control.sock"));
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));
            awaitStatus(client, endpoint, host, log);

            assertReceived(client.send(endpoint, new ControlCommand.Terminate(
                    1, ENVELOPE, AgentMessage.TerminationMode.GRACEFUL)), 1);
            assertThat(host.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(host.exitValue()).as(Files.readString(log)).isZero();

            JournalReadPage completed = awaitCommandResults(sessionDirectory, List.of(1L));
            assertThat(completed.issue()).isEmpty();
            assertSignal(completed.records(), 2, 15);
        } finally {
            if (host.isAlive()) {
                host.destroyForcibly();
                host.waitFor();
            }
        }
    }

    private Process startSessionHost(Path executable, String sessionId, Path sessionDirectory, Path log)
            throws Exception {
        return new ProcessBuilder(
                executable.toString(),
                "--session-id", sessionId,
                "--start-command-id", "command.start",
                "--session-dir", sessionDirectory.toString(),
                "--cwd", temporaryDirectory.toString(),
                "--cols", "80",
                "--rows", "24",
                "--term", "xterm-256color",
                "--",
                "/bin/cat")
                .redirectError(log.toFile())
                .redirectOutput(log.toFile())
                .start();
    }

    private Path extractSessionHost() throws Exception {
        String resource = "META-INF/orion/native/session-host/" + nativeTarget() + "/session-host";
        Path executable = temporaryDirectory.resolve("session-host");
        try (InputStream input = NativeControlLivePeerTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            Files.copy(input, executable);
        }
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private static String nativeTarget() {
        String architecture = System.getProperty("os.arch");
        String machine = architecture.equals("aarch64") || architecture.equals("arm64")
                ? "aarch64"
                : "x86_64";
        return machine + (System.getProperty("os.name").startsWith("Mac")
                ? "-apple-darwin"
                : "-unknown-linux-gnu");
    }

    private static int continueSignal() {
        return System.getProperty("os.name").startsWith("Mac") ? 19 : 18;
    }

    private static void assertReceived(ControlResult result, long sequence) {
        assertThat(result).isEqualTo(new ControlResult.Received(sequence));
    }

    private static JournalReadPage awaitCommandResults(Path directory, List<Long> expected)
            throws Exception {
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        Instant deadline = Instant.now().plus(TIMEOUT);
        JournalReadPage page;
        do {
            page = reader.readPage(directory, Optional.empty(), Optional.empty(), READ_LIMITS);
            List<Long> sequences = commandResultSequences(page.records());
            if (sequences.containsAll(expected)) {
                return page;
            }
            Thread.sleep(10);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("timed out waiting for COMMAND_RESULT records");
    }

    private static List<Long> commandResultSequences(List<SessionEventRecord> records) {
        List<Long> sequences = new ArrayList<>();
        for (SessionEventRecord record : records) {
            if (record.eventType() != 0x0002) {
                continue;
            }
            ByteBuffer payload = ByteBuffer.wrap(record.encodedPayload().toByteArray())
                    .order(ByteOrder.BIG_ENDIAN);
            assertThat(Byte.toUnsignedInt(payload.get())).isEqualTo(0x84);
            int sequencePrefix = Byte.toUnsignedInt(payload.get());
            long sequence = sequencePrefix <= 23
                    ? sequencePrefix
                    : unsignedLong(payload, sequencePrefix);
            int envelopePrefix = Byte.toUnsignedInt(payload.get());
            assertThat(envelopePrefix).isEqualTo(0x40 + ENVELOPE.toByteArray().length);
            byte[] envelope = new byte[ENVELOPE.toByteArray().length];
            payload.get(envelope);
            assertThat(envelope).isEqualTo(ENVELOPE.toByteArray());
            assertThat(Byte.toUnsignedInt(payload.get())).isEqualTo(1);
            assertThat(Byte.toUnsignedInt(payload.get())).isEqualTo(0x60);
            assertThat(payload.hasRemaining()).isFalse();
            sequences.add(sequence);
        }
        return sequences;
    }

    private static void assertSignal(List<SessionEventRecord> records, int kind, int platformCode) {
        byte[] expected = new byte[]{(byte) 0x82, (byte) kind, (byte) platformCode};
        boolean found = false;
        for (SessionEventRecord record : records) {
            if (record.eventType() == 0x0202
                    && Arrays.equals(record.encodedPayload().toByteArray(), expected)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("SIGNAL payload for kind %s and platform code %s", kind, platformCode).isTrue();
    }

    private static long unsignedLong(ByteBuffer payload, int prefix) {
        assertThat(prefix).isEqualTo(0x1b);
        return payload.getLong();
    }

    private static void awaitStatus(
            SessionControlClient client,
            ControlEndpoint endpoint,
            Process host,
            Path log
    ) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!host.isAlive()) {
                throw new AssertionError(
                        "session-host exited before accepting controls: " + Files.readString(log));
            }
            if (client.send(endpoint, new ControlCommand.Status()) instanceof ControlResult.Status) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for session-host: " + Files.readString(log));
    }
}
