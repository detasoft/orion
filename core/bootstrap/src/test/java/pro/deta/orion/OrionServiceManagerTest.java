package pro.deta.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionServiceManagerTest {

    @TempDir
    private Path tempDir;

    @Test
    void startLaunchesRunCommandAndWritesPidFile() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new RecordingProcess(42, true));
        OrionServiceManager manager = new OrionServiceManager(settings(), launcher, pid -> Optional.empty());

        int exitCode = manager.start(List.of("--config", "config.yml"), output(), output());

        assertEquals(0, exitCode);
        assertEquals(List.of("java", "-Xmx256m", "-jar", artifact().toString(), "run", "--config", "config.yml"),
                launcher.command);
        assertEquals("42", Files.readString(pidFile()).trim());
    }

    @Test
    void stopDestroysRunningProcessAndDeletesPidFile() throws Exception {
        Files.writeString(pidFile(), "42");
        RecordingProcess process = new RecordingProcess(42, true);
        OrionServiceManager manager = new OrionServiceManager(settings(), command -> {
            throw new AssertionError("start should not be called");
        }, pid -> Optional.of(process));

        int exitCode = manager.stop(output(), output());

        assertEquals(0, exitCode);
        assertTrue(process.destroyed);
        assertFalse(Files.exists(pidFile()));
    }

    @Test
    void restartStopsRunningProcessBeforeStartingNewOne() throws Exception {
        Files.writeString(pidFile(), "42");
        RecordingProcess oldProcess = new RecordingProcess(42, true);
        RecordingLauncher launcher = new RecordingLauncher(new RecordingProcess(43, true));
        OrionServiceManager manager = new OrionServiceManager(settings(), launcher, pid -> Optional.of(oldProcess));

        int exitCode = manager.restart(List.of("--config", "config.yml"), output(), output());

        assertEquals(0, exitCode);
        assertTrue(oldProcess.destroyed);
        assertEquals("43", Files.readString(pidFile()).trim());
        assertEquals(List.of("java", "-Xmx256m", "-jar", artifact().toString(), "run", "--config", "config.yml"),
                launcher.command);
    }

    @Test
    void startSplitsQuotedJavaOptions() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new RecordingProcess(42, true));
        OrionServiceManager manager = new OrionServiceManager(
                settings("-Xmx256m -Dservice.name='Orion Service'"),
                launcher,
                pid -> Optional.empty()
        );

        int exitCode = manager.start(List.of(), output(), output());

        assertEquals(0, exitCode);
        assertEquals(List.of("java", "-Xmx256m", "-Dservice.name=Orion Service", "-jar", artifact().toString(), "run"),
                launcher.command);
    }

    @Test
    void statusReturnsThreeWhenPidFileIsMissing() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OrionServiceManager manager = new OrionServiceManager(settings(), command -> {
            throw new AssertionError("start should not be called");
        }, pid -> Optional.empty());

        int exitCode = manager.status(new PrintStream(out, true, StandardCharsets.UTF_8), output());

        assertEquals(3, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("orion is not running"));
    }

    private OrionServiceManager.Settings settings() {
        return settings("-Xmx256m");
    }

    private OrionServiceManager.Settings settings(String javaOptions) {
        return new OrionServiceManager.Settings(
                "orion",
                tempDir,
                pidFile(),
                tempDir.resolve("orion.log"),
                Duration.ofSeconds(1),
                "java",
                javaOptions,
                artifact()
        );
    }

    private Path pidFile() {
        return tempDir.resolve("orion.pid");
    }

    private Path artifact() {
        return tempDir.resolve("orion.jar");
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static final class RecordingLauncher implements OrionServiceManager.ProcessLauncher {
        private final OrionServiceManager.ManagedProcess process;
        private List<String> command;

        private RecordingLauncher(OrionServiceManager.ManagedProcess process) {
            this.process = process;
        }

        @Override
        public OrionServiceManager.ManagedProcess start(List<String> command) {
            this.command = command;
            return process;
        }
    }

    private static final class RecordingProcess implements OrionServiceManager.ManagedProcess {
        private final long pid;
        private boolean alive;
        private boolean destroyed;

        private RecordingProcess(long pid, boolean alive) {
            this.pid = pid;
            this.alive = alive;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean destroy() {
            destroyed = true;
            alive = false;
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            alive = false;
            return true;
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return !alive;
        }
    }
}
