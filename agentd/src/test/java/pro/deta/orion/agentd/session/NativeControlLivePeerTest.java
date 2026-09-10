package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandOutcome;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agentd.journal.FileSystemSessionJournalReader;
import pro.deta.orion.agentd.journal.JournalReadLimits;
import pro.deta.orion.agentd.journal.JournalReadPage;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class NativeControlLivePeerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ProtocolBytes ENVELOPE =
            ProtocolBytes.copyOf(new byte[]{(byte) 0x84, 1, 2, 3, 0x66, 'f', 'u', 't', 'u', 'r', 'e'});
    private static final Optional<ProtocolBytes> SERVER_ENVELOPE = Optional.of(ENVELOPE);
    private static final SessionEventCodec EVENT_CODEC =
            new SessionEventCodec(AgentProtocolLimits.journalDefaults());
    private static final JournalReadLimits READ_LIMITS =
            new JournalReadLimits(100, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);

    @TempDir
    Path temporaryDirectory;

    @Test
    void listsAndSignalsAProcessThroughTheRealHost() throws Exception {
        Path executable = extractSessionHost();
        Path directory = Files.createDirectory(temporaryDirectory.resolve("processes"));
        Path log = temporaryDirectory.resolve("processes.log");
        Process host = startSessionHost(executable, "java-process-list", directory, log);
        ControlEndpoint endpoint = new ControlEndpoint(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                "control.sock", directory.resolve("control.sock"));
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));
        try {
            awaitStatus(client, endpoint, host, log);
            ControlResult result = client.send(endpoint, new ControlCommand.ListProcesses());
            assertThat(result).isInstanceOf(ControlResult.Processes.class);
            List<ControlResult.Process> processes = ((ControlResult.Processes) result).processes();
            assertThat(processes).hasSize(1);
            assertThat(processes.getFirst().originalRoot()).isTrue();
            ControlCommand.Signal signal = new ControlCommand.Signal(1, SessionCommandSource.MANUAL,
                    Optional.empty(), AgentMessage.SignalKind.PLATFORM, continueSignal(),
                    OptionalLong.of(processes.getFirst().token()));
            assertReceived(client.send(endpoint, signal), 1);
            JournalReadPage page = awaitCommandResults(directory, List.of(1L));
            assertCommandResult(page.records(), SessionCommandSource.MANUAL, 1, operationPayload(signal));
            assertReceived(client.send(endpoint, new ControlCommand.Terminate(1, SessionCommandSource.SERVER,
                    SERVER_ENVELOPE, AgentMessage.TerminationMode.FORCE)), 1);
            assertThat(host.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(host.exitValue()).as(Files.readString(log)).isZero();
        } finally {
            if (host.isAlive()) {
                client.send(endpoint, new ControlCommand.Terminate(2, SessionCommandSource.MANUAL,
                        Optional.empty(), AgentMessage.TerminationMode.FORCE));
                if (!host.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    host.destroyForcibly();
                    host.waitFor();
                }
            }
        }
    }

    @Test
    void interleavesServerAndRepeatedManualOperationsWithTheRealSessionHost() throws Exception {
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

            ControlCommand.Input serverInput = new ControlCommand.Input(
                    42,
                    SessionCommandSource.SERVER,
                    SERVER_ENVELOPE,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    ProtocolBytes.copyOf(new byte[]{'o', 'k', '\n'}));
            ControlCommand.Resize firstManualResize = new ControlCommand.Resize(
                    1, SessionCommandSource.MANUAL, Optional.empty(), 100, 30);
            ControlCommand.Signal serverSignal = new ControlCommand.Signal(
                    43, SessionCommandSource.SERVER, SERVER_ENVELOPE,
                    AgentMessage.SignalKind.PLATFORM, continueSignal(), OptionalLong.empty());
            ControlCommand.Resize repeatedManualResize = new ControlCommand.Resize(
                    1, SessionCommandSource.MANUAL, Optional.empty(), 101, 31);
            assertReceived(client.send(endpoint, serverInput), 42);
            assertReceived(client.send(endpoint, firstManualResize), 1);
            assertReceived(client.send(endpoint, serverSignal), 43);
            assertReceived(client.send(endpoint, repeatedManualResize), 1);
            assertThat(client.send(endpoint, serverSignal)).isInstanceOf(ControlResult.Rejected.class);

            JournalReadPage effects = awaitCommandResults(
                    sessionDirectory, List.of(42L, 1L, 43L), 4);
            assertCommandResult(
                    effects.records(), SessionCommandSource.SERVER, 42, ENVELOPE.toByteArray());
            assertCommandResult(
                    effects.records(), SessionCommandSource.MANUAL, 1, operationPayload(firstManualResize));
            assertCommandResult(
                    effects.records(), SessionCommandSource.MANUAL, 1, operationPayload(repeatedManualResize));
            long acknowledgedEventId = effects.records().getLast().eventId().value();
            ControlCommand.AckJournal acknowledgement = new ControlCommand.AckJournal(
                    2, SessionCommandSource.MANUAL, Optional.empty(), acknowledgedEventId);
            assertReceived(client.send(endpoint, acknowledgement), 2);
            awaitCommandResults(sessionDirectory, List.of(2L), 5);

            try (SocketChannel stale = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                stale.connect(UnixDomainSocketAddress.of(sessionDirectory.resolve("control.sock")));
                assertThat(client.send(endpoint, new ControlCommand.ClaimServerControl(OptionalLong.of(43))))
                        .isEqualTo(new ControlResult.ServerControlClaimed(
                                OptionalLong.of(43), OptionalLong.of(acknowledgedEventId)));

                ControlCommand.Resize fenced = new ControlCommand.Resize(
                        44, SessionCommandSource.SERVER, SERVER_ENVELOPE, 102, 32);
                writeFully(stale, new NativeControlCodec().encode(fenced));
                assertThat(new NativeControlCodec().decode(fenced, readFrame(stale)))
                        .isInstanceOf(ControlResult.Rejected.class);
            }

            ControlCommand.Terminate terminate = new ControlCommand.Terminate(
                    3, SessionCommandSource.MANUAL, Optional.empty(), AgentMessage.TerminationMode.FORCE);
            assertReceived(client.send(endpoint, terminate), 3);
            assertThat(host.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(host.exitValue()).as(Files.readString(log)).isZero();

            JournalReadPage completed = awaitCommandResults(
                    sessionDirectory, List.of(42L, 1L, 43L, 2L, 3L), 6);
            assertThat(completed.issue()).isEmpty();
            assertSignal(completed.records(), 3, 9);
            assertCommandResult(
                    completed.records(), SessionCommandSource.MANUAL, 2, operationPayload(acknowledgement));
            assertCommandResult(
                    completed.records(), SessionCommandSource.MANUAL, 3, operationPayload(terminate));
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
                    1, SessionCommandSource.SERVER,
                    SERVER_ENVELOPE, AgentMessage.TerminationMode.GRACEFUL)), 1);
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
        Path builtExecutable = Path.of("../session-host/target/cargo/debug/session-host");
        assertThat(builtExecutable).isRegularFile().isExecutable();
        Path executable = temporaryDirectory.resolve("session-host");
        Files.copy(builtExecutable, executable);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private static int continueSignal() {
        return System.getProperty("os.name").startsWith("Mac") ? 19 : 18;
    }

    private static byte[] readFrame(SocketChannel channel) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(NativeControlCodec.HEADER_LENGTH);
        readFully(channel, header);
        int payloadLength = ByteBuffer.wrap(header.array()).order(ByteOrder.LITTLE_ENDIAN).getInt(24);
        ByteBuffer frame = ByteBuffer.allocate(NativeControlCodec.HEADER_LENGTH + payloadLength);
        frame.put(header.array());
        readFully(channel, frame.slice());
        return frame.array();
    }

    private static void readFully(SocketChannel channel, ByteBuffer target) throws Exception {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw new AssertionError("session-host closed an incomplete control response");
            }
        }
    }

    private static void writeFully(SocketChannel channel, byte[] bytes) throws Exception {
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) {
            channel.write(source);
        }
    }

    private static void assertReceived(ControlResult result, long sequence) {
        assertThat(result).isEqualTo(new ControlResult.Received(sequence));
    }

    private static JournalReadPage awaitCommandResults(Path directory, List<Long> expected)
            throws Exception {
        return awaitCommandResults(directory, expected, expected.size());
    }

    private static JournalReadPage awaitCommandResults(
            Path directory, List<Long> expected, int minimumResultCount) throws Exception {
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        Instant deadline = Instant.now().plus(TIMEOUT);
        JournalReadPage page;
        do {
            page = reader.readPage(directory, Optional.empty(), Optional.empty(), READ_LIMITS);
            List<Long> sequences = commandResultSequences(page.records());
            if (sequences.containsAll(expected) && sequences.size() >= minimumResultCount) {
                return page;
            }
            Thread.sleep(10);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("timed out waiting for COMMAND_RESULT records");
    }

    private static byte[] operationPayload(ControlCommand command) {
        byte[] frame = new NativeControlCodec().encode(command);
        return Arrays.copyOfRange(frame, NativeControlCodec.HEADER_LENGTH, frame.length);
    }

    private static void assertCommandResult(
            List<SessionEventRecord> records,
            SessionCommandSource source,
            long sequence,
            byte[] sourceEnvelope
    ) throws Exception {
        List<SessionEventPayload.CommandResult> results = new ArrayList<>();
        for (SessionEventRecord record : records) {
            if (record.eventType() == 0x0002) {
                results.add((SessionEventPayload.CommandResult)
                        EVENT_CODEC.decodeKnownPayload(record).orElseThrow());
            }
        }
        assertThat(results).anySatisfy(result -> {
            assertThat(result.source()).isEqualTo(source);
            assertThat(result.operationSequence()).isEqualTo(sequence);
            assertThat(result.sourceEnvelope().toByteArray()).containsExactly(sourceEnvelope);
            assertThat(result.outcome()).isEqualTo(SessionCommandOutcome.SUCCEEDED);
            assertThat(result.detail()).isEmpty();
        });
    }

    private static List<Long> commandResultSequences(List<SessionEventRecord> records) throws Exception {
        List<Long> sequences = new ArrayList<>();
        for (SessionEventRecord record : records) {
            if (record.eventType() != 0x0002) {
                continue;
            }
            SessionEventPayload.CommandResult result = (SessionEventPayload.CommandResult)
                    EVENT_CODEC.decodeKnownPayload(record).orElseThrow();
            assertThat(result.outcome()).isEqualTo(SessionCommandOutcome.SUCCEEDED);
            assertThat(result.detail()).isEmpty();
            sequences.add(result.operationSequence());
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
