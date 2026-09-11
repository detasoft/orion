package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PosixTerminalTest {
    @Test
    void closingMacTerminalUnblocksAConcurrentInputRead() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(
                "script", "-q", "/dev/null", "/bin/sh", "-c",
                "stty rows 24 cols 80; exec \"$@\"", "terminal-close-probe",
                java.toString(), "-cp", System.getProperty("java.class.path"), CloseProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            String output = exited
                    ? new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : "";
            assertThat(exited).as("probe output: %s", output).isTrue();
            assertThat(process.exitValue()).as("probe output: %s", output).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void acquiresRawModeReportsResizeAndRestoresExactlyOnce() throws Exception {
        RecordingPlatform platform = new RecordingPlatform();
        RecordingHooks hooks = new RecordingHooks();
        PosixTerminal terminal = new PosixTerminal(platform, hooks);

        assertThat(terminal.size()).isEqualTo(new TerminalSize(80, 24));
        platform.size = new TerminalSize(120, 40);
        assertThat(terminal.awaitResize(Duration.ofMillis(1)))
                .contains(new TerminalSize(120, 40));
        assertThat(terminal.awaitResize(Duration.ofMillis(1))).isEmpty();

        terminal.close();
        terminal.close();

        assertThat(platform.calls).containsExactly("state", "raw", "size", "size", "size", "restore:saved");
        assertThat(hooks.added).hasSize(1);
        assertThat(hooks.removed).containsExactlyElementsOf(hooks.added);
    }

    @Test
    void failedRawModeAcquisitionRestoresTheSavedState() {
        RecordingPlatform platform = new RecordingPlatform();
        platform.rawFailure = new IOException("raw failed");

        assertThatThrownBy(() -> new PosixTerminal(platform, new RecordingHooks()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("raw failed");

        assertThat(platform.calls).containsExactly("state", "raw", "restore:saved");
    }

    @Test
    void failedStateCaptureClosesBothTerminalStreamsWithoutAttemptingRestore() {
        RecordingPlatform platform = new RecordingPlatform();
        platform.stateFailure = new IOException("state failed");

        assertThatThrownBy(() -> new PosixTerminal(platform, new RecordingHooks()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("state failed");

        assertThat(platform.calls).containsExactly("state");
        assertThat(platform.input.closed).isTrue();
        assertThat(platform.output.closed).isTrue();
    }

    @Test
    void unexpectedHookRemovalFailureStillClosesBothTerminalStreams() throws Exception {
        RecordingPlatform platform = new RecordingPlatform();
        RecordingHooks hooks = new RecordingHooks();
        hooks.removeFailure = new UnsupportedOperationException("remove failed");
        PosixTerminal terminal = new PosixTerminal(platform, hooks);

        assertThatThrownBy(terminal::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("remove failed");

        assertThat(platform.calls).containsExactly("state", "raw", "size", "restore:saved");
        assertThat(platform.input.closed).isTrue();
        assertThat(platform.output.closed).isTrue();
    }

    private static final class RecordingPlatform implements PosixTerminal.Platform {
        private final List<String> calls = new ArrayList<>();
        private final RecordingInput input = new RecordingInput();
        private final RecordingOutput output = new RecordingOutput();
        private TerminalSize size = new TerminalSize(80, 24);
        private IOException stateFailure;
        private IOException rawFailure;

        @Override
        public String state() throws IOException {
            calls.add("state");
            if (stateFailure != null) {
                throw stateFailure;
            }
            return "saved";
        }

        @Override
        public void raw() throws IOException {
            calls.add("raw");
            if (rawFailure != null) {
                throw rawFailure;
            }
        }

        @Override
        public void restore(String state) {
            calls.add("restore:" + state);
        }

        @Override
        public TerminalSize size() {
            calls.add("size");
            return size;
        }

        @Override
        public RecordingInput input() {
            return input;
        }

        @Override
        public RecordingOutput output() {
            return output;
        }
    }

    private static final class RecordingInput extends ByteArrayInputStream {
        private boolean closed;

        private RecordingInput() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class RecordingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class RecordingHooks implements PosixTerminal.Hooks {
        private final List<Thread> added = new ArrayList<>();
        private final List<Thread> removed = new ArrayList<>();
        private RuntimeException removeFailure;

        @Override
        public void add(Thread hook) {
            added.add(hook);
        }

        @Override
        public void remove(Thread hook) {
            removed.add(hook);
            if (removeFailure != null) {
                throw removeFailure;
            }
        }
    }

    public static final class CloseProbe {
        public static void main(String[] arguments) throws Exception {
            PosixTerminal terminal = PosixTerminal.acquire();
            CountDownLatch reading = new CountDownLatch(1);
            Thread reader = Thread.startVirtualThread(() -> {
                reading.countDown();
                try {
                    terminal.input().read();
                } catch (IOException ignored) {
                    // Closing the channel ends the pending terminal read.
                }
            });
            if (!reading.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("terminal reader did not start");
            }
            Thread.sleep(100);
            terminal.close();
            reader.join(1_000);
            if (reader.isAlive()) {
                throw new IllegalStateException("terminal reader did not stop");
            }
        }
    }
}
