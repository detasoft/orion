package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentMessage;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        }, new PrintStream(output), new PrintStream(errors), (directory, attachErrors) -> 0);

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
                (directory, attachErrors) -> 0);
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
                    sessionDirectory, new PrintStream(new ByteArrayOutputStream()));

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
                    sessionDirectory, new PrintStream(new ByteArrayOutputStream()));

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

    private static LocalTerminalAttacher attacher(
            SessionControlClient client,
            TerminalDevice terminal
    ) {
        return new LocalTerminalAttacher(
                new JsonSessionManifestReader(),
                new ControlHostProbe(client),
                () -> terminal,
                client::send);
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
            return output;
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
