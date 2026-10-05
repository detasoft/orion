package pro.deta.orion.agentd.terminal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.ControlHostProbe;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalSessionLaunchLivePeerTest {
    @TempDir
    Path temporaryDirectory;
    private Path stateDirectory;

    @AfterEach
    void removeShortStateDirectory() throws Exception {
        if (stateDirectory == null || !Files.exists(stateDirectory)) {
            return;
        }
        Files.walkFileTree(stateDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws java.io.IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, java.io.IOException failure)
                    throws java.io.IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    void returnsAfterDurableHandoffAndLeavesTheRealHostRunning() throws Exception {
        stateDirectory = Files.createTempDirectory(Path.of("/tmp"), "orion-local-launch-");
        Path sessionDirectory = stateDirectory.resolve("sessions/local-live");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exit = LocalSessionLauncher.run(new String[]{
                "--state-dir", stateDirectory.toString(),
                "--session-id", "local-live",
                "--cwd", temporaryDirectory.toString(),
                "--", "/bin/cat"
        }, new PrintStream(output), new PrintStream(errors),
                (directory, acknowledgeJournal, attachErrors) -> 0);

        String hostLog = Files.exists(sessionDirectory.resolve("session-host.log"))
                ? Files.readString(sessionDirectory.resolve("session-host.log")) : "no host log";
        assertThat(exit).as(errors.toString(StandardCharsets.UTF_8) + hostLog).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("session=local-live directory=" + sessionDirectory + "\n");
        assertThat(stateDirectory.resolve("runtime/session-host")).isRegularFile().isExecutable();
        SessionManifest manifest = new JsonSessionManifestReader().read(sessionDirectory);
        ProcessHandle host = ProcessHandle.of(manifest.hostPid()).orElseThrow();
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
        try {
            assertThat(host.isAlive()).isTrue();
            assertThat(client.send(manifest.control(), new ControlCommand.Status()))
                    .isInstanceOf(ControlResult.Status.class);
        } finally {
            client.send(manifest.control(), new ControlCommand.Terminate(
                    1,
                    SessionCommandSource.MANUAL,
                    Optional.empty(),
                    AgentMessage.TerminationMode.FORCE));
            if (host.isAlive()) {
                host.onExit().get(5, TimeUnit.SECONDS);
            }
            if (host.isAlive()) {
                host.destroyForcibly();
            }
        }
    }

    @Test
    void detachesAndReattachesToARealPosixHostWithoutAServer() throws Exception {
        Path executable = extractSessionHost();
        stateDirectory = Files.createTempDirectory(Path.of("/tmp"), "orion-local-attach-");
        Path sessionDirectory = stateDirectory.resolve("sessions/local-attach");
        ByteArrayOutputStream launchErrors = new ByteArrayOutputStream();
        int launch = LocalSessionLauncher.run(new String[]{
                "--session-host", executable.toString(),
                "--state-dir", stateDirectory.toString(),
                "--session-id", "local-attach",
                "--cwd", temporaryDirectory.toString(),
                "--", "/bin/sh", "-c",
                "printf 'ready\\n'; IFS= read -r line; printf 'got:%s size:' \"$line\"; stty size; "
                        + "IFS= read -r line; exit 7"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(launchErrors),
                (directory, acknowledgeJournal, attachErrors) -> 0);
        assertThat(launch).as(launchErrors.toString(StandardCharsets.UTF_8)).isZero();

        SessionManifest manifest = new JsonSessionManifestReader().read(sessionDirectory);
        ProcessHandle host = ProcessHandle.of(manifest.hostPid()).orElseThrow();
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
        try {
            ScriptedTerminal firstTerminal = new ScriptedTerminal(
                    new TerminalSize(100, 30),
                    new InputStep("", "hello\n"),
                    new InputStep("30 100", new byte[]{0x1d, 'd'}));
            int firstExit = attacher(client, firstTerminal).attach(
                    sessionDirectory, false, new PrintStream(new ByteArrayOutputStream()));

            assertThat(firstExit).isZero();
            assertThat(firstTerminal.text()).contains("ready", "got:hello", "30 100");
            assertThat(firstTerminal.closed.get()).isTrue();
            assertThat(host.isAlive()).isTrue();
            assertThat(client.send(manifest.control(), new ControlCommand.Status()))
                    .isInstanceOfSatisfying(ControlResult.Status.class, live -> {
                        assertThat(live.status().hostLive()).isTrue();
                        assertThat(live.status().childLive()).isTrue();
                        assertThat(live.status().columns()).isEqualTo(100);
                        assertThat(live.status().rows()).isEqualTo(30);
                    });

            ScriptedTerminal secondTerminal = new ScriptedTerminal(
                    new TerminalSize(100, 30), new InputStep("got:hello", "quit\n"));
            int secondExit = attacher(client, secondTerminal).attach(
                    sessionDirectory, false, new PrintStream(new ByteArrayOutputStream()));

            assertThat(secondExit).isEqualTo(7);
            assertThat(secondTerminal.text()).contains("ready", "got:hello", "30 100", "quit");
            assertThat(secondTerminal.closed.get()).isTrue();
            host.onExit().get(5, TimeUnit.SECONDS);
        } finally {
            if (host.isAlive()) {
                client.send(manifest.control(), new ControlCommand.Terminate(
                        1,
                        SessionCommandSource.MANUAL,
                        Optional.empty(),
                        AgentMessage.TerminationMode.FORCE));
                host.onExit().get(5, TimeUnit.SECONDS);
            }
            if (host.isAlive()) {
                host.destroyForcibly();
            }
        }
    }

    @Test
    void acknowledgesRealRotatedHistoryOnlyAfterDeliveryAndReattachesAfterRetention() throws Exception {
        Path executable = extractSessionHost();
        stateDirectory = Files.createTempDirectory(Path.of("/tmp"), "orion-local-ack-");
        Path sessionDirectory = stateDirectory.resolve("ack-live");
        Process host = new ProcessBuilder(executable.toString(),
                "--session-id", "ack-live", "--start-command-id", "start-ack",
                "--session-dir", sessionDirectory.toString(), "--cwd", temporaryDirectory.toString(),
                "--cols", "80", "--rows", "24", "--journal-segment-bytes", "1", "--journal-max-bytes", "1",
                "--", "/bin/sh", "-c",
                "printf 'ready\\n'; while IFS= read -r line; do printf 'reply:%s\\n' \"$line\"; done")
                .redirectErrorStream(true).redirectOutput(stateDirectory.resolve("host.log").toFile()).start();
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
        try {
            awaitCondition(() -> Files.exists(sessionDirectory.resolve("metadata")));
            SessionManifest manifest = new JsonSessionManifestReader().read(sessionDirectory);
            awaitCondition(() -> client.send(manifest.control(), new ControlCommand.Status())
                    instanceof ControlResult.Status);
            awaitCondition(() -> {
                ByteArrayOutputStream replay = new ByteArrayOutputStream();
                new TerminalJournalFollower().follow(sessionDirectory, replay, () -> true, ignored -> {});
                return replay.toString(StandardCharsets.UTF_8).contains("ready");
            });
            long outputSegment = Collections.max(journalSegments(sessionDirectory));
            assertThat(client.send(manifest.control(), new ControlCommand.Resize(1,
                    SessionCommandSource.MANUAL, Optional.empty(), 80, 24)))
                    .isInstanceOf(ControlResult.Received.class);
            awaitCondition(() -> Collections.max(journalSegments(sessionDirectory)) > outputSegment);
            Path retentionState = sessionDirectory.resolve("control-retention-state");
            Set<Long> originalSegments = journalSegments(sessionDirectory);
            assertThat(originalSegments).hasSizeGreaterThan(1);
            Set<Long> originalClosedSegments = new HashSet<>(originalSegments);
            originalClosedSegments.remove(Collections.max(originalSegments));

            ScriptedTerminal failedTerminal = new ScriptedTerminal(new TerminalSize(80, 24));
            failedTerminal.outputOverride = new PrintStream(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("terminal closed");
                }
            });
            assertThat(attacher(client, failedTerminal).attach(sessionDirectory, true,
                    new PrintStream(new ByteArrayOutputStream()))).isEqualTo(1);
            assertThat(retentionState).doesNotExist();
            assertThat(journalSegments(sessionDirectory)).containsAll(originalSegments);

            ScriptedTerminal defaultTerminal = new ScriptedTerminal(new TerminalSize(80, 24),
                    new InputStep("ready", new byte[]{0x1d, 'd'}));
            assertThat(attacher(client, defaultTerminal).attach(sessionDirectory, false,
                    new PrintStream(new ByteArrayOutputStream()))).isZero();
            assertThat(retentionState).doesNotExist();
            assertThat(journalSegments(sessionDirectory)).containsAll(originalSegments);

            long firstWatermark;
            ScriptedTerminal first = new ScriptedTerminal(new TerminalSize(80, 24));
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Integer> result = executor.submit(() -> attacher(client, first).attach(
                        sessionDirectory, true,
                        new PrintStream(new ByteArrayOutputStream())));
                try {
                    awaitCondition(() -> watermark(retentionState) > 0);
                    assertThat(first.text()).contains("ready");
                    firstWatermark = watermark(retentionState);
                    awaitCondition(() -> Collections.disjoint(
                            journalSegments(sessionDirectory), originalClosedSegments));
                } finally {
                    first.close();
                }
                assertThat(result.get(5, TimeUnit.SECONDS)).isZero();
            }

            ScriptedTerminal second = new ScriptedTerminal(new TerminalSize(80, 24));
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Integer> result = executor.submit(() -> attacher(client, second).attach(
                        sessionDirectory, true,
                        new PrintStream(new ByteArrayOutputStream())));
                try {
                    assertThat(client.send(manifest.control(), new ControlCommand.Input(2,
                            SessionCommandSource.MANUAL, Optional.empty(), UUID.randomUUID(),
                            ProtocolBytes.copyOf(
                                    "again\n".getBytes(StandardCharsets.UTF_8)))))
                            .isInstanceOf(ControlResult.Received.class);
                    awaitCondition(() -> second.text().contains("reply:again"));
                    awaitCondition(() -> watermark(retentionState) > firstWatermark);
                    assertThat(second.text()).doesNotContain("ready");
                } finally {
                    second.close();
                }
                assertThat(result.get(5, TimeUnit.SECONDS)).isZero();
            }
            assertThat(host.isAlive()).isTrue();
            assertThat(Files.readString(retentionState)).contains("acknowledgedEventId");
        } finally {
            try {
                if (Files.exists(sessionDirectory.resolve("metadata"))) {
                    SessionManifest manifest = new JsonSessionManifestReader().read(sessionDirectory);
                    client.send(manifest.control(), new ControlCommand.Terminate(3,
                            SessionCommandSource.MANUAL, Optional.empty(), AgentMessage.TerminationMode.FORCE));
                }
            } finally {
                if (!host.waitFor(5, TimeUnit.SECONDS)) {
                    host.destroyForcibly();
                    assertThat(host.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static Set<Long> journalSegments(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            Set<Long> segments = new HashSet<>();
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.matches("[0-9]+\\.cbor(\\.zst)?")) {
                    segments.add(Long.parseLong(name.substring(0, name.indexOf('.'))));
                }
            }
            return segments;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long watermark(Path state) {
        if (!Files.exists(state)) {
            return 0;
        }
        try (JsonParser parser = new JsonFactory()
                .createParser(Files.readAllBytes(state))) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME
                        && "acknowledgedEventId".equals(parser.currentName())) {
                    parser.nextToken();
                    return parser.getLongValue();
                }
            }
            throw new AssertionError("missing host retention watermark");
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(ready.getAsBoolean()).isTrue();
    }

    private static LocalTerminalAttacher attacher(
            SessionControlClient client,
            TerminalDevice terminal
    ) {
        return new LocalTerminalAttacher(
                new JsonSessionManifestReader(),
                new ControlHostProbe(client),
                () -> terminal,
                client::open);
    }

    private Path extractSessionHost() throws Exception {
        Path builtExecutable = Path.of("../session-host/target/cargo/debug/session-host");
        assertThat(builtExecutable).isRegularFile().isExecutable();
        Path executable = temporaryDirectory.resolve("session-host");
        Files.copy(builtExecutable, executable);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private record InputStep(String awaitedOutput, byte[] bytes) {
        private InputStep(String awaitedOutput, String text) {
            this(awaitedOutput, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class ScriptedTerminal implements TerminalDevice {
        private final TerminalSize size;
        private final InputStep[] steps;
        private final NotifyingOutput output = new NotifyingOutput();
        private final AtomicBoolean closed = new AtomicBoolean();
        private OutputStream outputOverride;
        private int step;

        private ScriptedTerminal(TerminalSize size, InputStep... steps) {
            this.size = size;
            this.steps = steps;
        }

        @Override
        public InputStream input() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] single = new byte[1];
                    int length = read(single, 0, 1);
                    return length < 0 ? -1 : Byte.toUnsignedInt(single[0]);
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    synchronized (output) {
                        while (step >= steps.length && !closed.get()) {
                            waitForOutput();
                        }
                        if (closed.get()) {
                            return -1;
                        }
                        InputStep current = steps[step];
                        while (!output.text().contains(current.awaitedOutput()) && !closed.get()) {
                            waitForOutput();
                        }
                        if (closed.get()) {
                            return -1;
                        }
                        int copied = Math.min(length, current.bytes().length);
                        System.arraycopy(current.bytes(), 0, buffer, offset, copied);
                        step++;
                        return copied;
                    }
                }

                private void waitForOutput() throws IOException {
                    try {
                        output.wait(5_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("scripted terminal input interrupted", exception);
                    }
                }
            };
        }

        @Override
        public OutputStream output() {
            return outputOverride == null ? output : outputOverride;
        }

        @Override
        public TerminalSize size() {
            return size;
        }

        @Override
        public Optional<TerminalSize> awaitResize(Duration interval) throws InterruptedException {
            Thread.sleep(interval);
            return Optional.empty();
        }

        @Override
        public void close() {
            closed.set(true);
            synchronized (output) {
                output.notifyAll();
            }
        }

        private String text() {
            return output.text();
        }
    }

    private static final class NotifyingOutput extends ByteArrayOutputStream {
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            super.write(bytes, offset, length);
            notifyAll();
        }

        private synchronized String text() {
            return toString(StandardCharsets.UTF_8);
        }
    }
}
