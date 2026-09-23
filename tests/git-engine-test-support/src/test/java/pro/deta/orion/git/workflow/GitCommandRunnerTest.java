package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.LINUX, OS.MAC})
class GitCommandRunnerTest {
    @TempDir
    Path directory;

    @Test
    void preservesOutputExitStatusAndCommandDiagnostics() throws Exception {
        Path executable = executable("printf 'output\\n'\nprintf 'diagnostic\\n' >&2\nexit 7\n");
        GitCommandRunner runner = new GitCommandRunner(executable.toString(), Duration.ofSeconds(5));

        GitCommandRunner.Result result = runner.runResult(directory, "test-command");

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.output()).isEqualTo("output\ndiagnostic\n");
        assertThat(result.command()).contains(executable.toString()).endsWith("test-command");
        assertThatThrownBy(result::requireSuccess).isInstanceOf(IOException.class)
                .hasMessageContaining("exit 7").hasMessageContaining("output\ndiagnostic");

        Files.writeString(executable, "#!/bin/sh\nprintf 'success\\n'\n");
        assertThat(runner.run(directory, "test-command").trimmed()).isEqualTo("success");
    }

    @Test
    void stopsChildBeforeReportingTimeout() throws Exception {
        try (Invocation invocation = new Invocation(Duration.ofSeconds(3), true)) {
            ProcessHandle child = invocation.awaitChild();

            invocation.awaitFinished();

            assertThat(invocation.failure.get()).isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out after PT3S").hasMessageContaining("ready");
            assertThat(invocation.interrupted).isFalse();
            assertThat(Files.exists(directory.resolve("terminating"))).isTrue();
            assertThat(child.isAlive()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsChildAndPreservesInterruption(boolean stalledShutdown) throws Exception {
        try (Invocation invocation = new Invocation(Duration.ofSeconds(30), stalledShutdown)) {
            ProcessHandle child = invocation.awaitChild();

            invocation.caller.interrupt();
            if (stalledShutdown) {
                awaitFile(directory.resolve("terminating"));
                invocation.caller.interrupt();
            }
            invocation.awaitFinished();

            assertThat(invocation.failure.get()).isInstanceOf(IOException.class)
                    .hasMessage("Interrupted while waiting for canonical Git")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(invocation.interrupted).isTrue();
            assertThat(child.isAlive()).isFalse();
        }
    }

    private Path executable(String body) throws IOException {
        Path executable = directory.resolve("git-fixture");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(file).exists();
    }

    private final class Invocation implements AutoCloseable {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final Thread caller;

        private Invocation(Duration timeout, boolean stalledShutdown) throws IOException {
            Path executable = executable("exec \"$ORION_TEST_JAVA\" -cp \"$ORION_TEST_CLASSPATH\" "
                    + "'pro.deta.orion.git.workflow.GitCommandRunnerTest$BlockingChild'\n");
            GitCommandRunner runner = new GitCommandRunner(executable.toString(), timeout);
            Map<String, String> environment = Map.of(
                    "ORION_TEST_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "ORION_TEST_CLASSPATH", System.getProperty("java.class.path"),
                    "ORION_TEST_DIRECTORY", directory.toString(),
                    "ORION_TEST_STALL_SHUTDOWN", Boolean.toString(stalledShutdown));
            caller = Thread.ofPlatform().start(() -> {
                try {
                    runner.runResult(directory, environment);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
        }

        private ProcessHandle awaitChild() throws Exception {
            awaitFile(directory.resolve("ready"));
            return ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve("pid")))).orElseThrow();
        }

        private void awaitFinished() throws InterruptedException {
            caller.join(Duration.ofSeconds(10));
            assertThat(caller.isAlive()).isFalse();
        }

        @Override
        public void close() throws Exception {
            caller.interrupt();
            Path pidFile = directory.resolve("pid");
            if (Files.exists(pidFile)) {
                var child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
                if (child.isPresent() && child.get().isAlive()) {
                    child.get().destroyForcibly();
                    child.get().onExit().get(5, TimeUnit.SECONDS);
                }
            }
            caller.join(Duration.ofSeconds(10));
        }
    }

    public static final class BlockingChild {
        public static void main(String[] arguments) throws Exception {
            Path directory = Path.of(System.getenv("ORION_TEST_DIRECTORY"));
            if (Boolean.parseBoolean(System.getenv("ORION_TEST_STALL_SHUTDOWN"))) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.writeString(directory.resolve("terminating"), "");
                        new CountDownLatch(1).await();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }));
            }
            Files.writeString(directory.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
            System.out.println("ready");
            Files.writeString(directory.resolve("ready"), "");
            new CountDownLatch(1).await();
        }
    }
}
