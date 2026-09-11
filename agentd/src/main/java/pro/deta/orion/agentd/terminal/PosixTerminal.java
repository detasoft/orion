package pro.deta.orion.agentd.terminal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class PosixTerminal implements TerminalDevice {
    private final Platform platform;
    private final Hooks hooks;
    private final String state;
    private final Thread shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean();
    private TerminalSize lastSize;

    PosixTerminal(Platform platform, Hooks hooks) throws IOException {
        this.platform = platform;
        this.hooks = hooks;
        String capturedState = null;
        boolean raw = false;
        try {
            capturedState = platform.state();
            platform.raw();
            raw = true;
            lastSize = platform.size();
            state = capturedState;
            shutdownHook = new Thread(this::closeDuringShutdown, "agentd-terminal-restore");
            hooks.add(shutdownHook);
        } catch (IOException | RuntimeException failure) {
            if (raw || capturedState != null) {
                try {
                    platform.restore(capturedState);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            closeStreams(platform, failure);
            throw failure;
        }
    }

    static PosixTerminal acquire() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux") && !os.contains("mac")) {
            throw new IOException("local terminal attach requires macOS or Linux");
        }
        return new PosixTerminal(new NativePlatform(), new RuntimeHooks());
    }

    @Override
    public InputStream input() {
        return platform.input();
    }

    @Override
    public OutputStream output() {
        return platform.output();
    }

    @Override
    public TerminalSize size() {
        return lastSize;
    }

    @Override
    public Optional<TerminalSize> awaitResize(Duration interval) throws IOException, InterruptedException {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("resize interval must be positive");
        }
        TimeUnit.NANOSECONDS.sleep(interval.toNanos());
        TerminalSize current = platform.size();
        if (current.equals(lastSize)) {
            return Optional.empty();
        }
        lastSize = current;
        return Optional.of(current);
    }

    @Override
    public void close() throws IOException {
        close(false);
    }

    private void closeDuringShutdown() {
        try {
            close(true);
        } catch (IOException ignored) {
            // The JVM is already shutting down; restoration was still attempted.
        }
    }

    private void close(boolean shuttingDown) throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            platform.restore(state);
        } catch (IOException exception) {
            failure = exception;
        }
        if (!shuttingDown) {
            try {
                hooks.remove(shutdownHook);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // Shutdown started or the hook is currently running.
            } catch (RuntimeException exception) {
                failure = addFailure(failure, new IOException(exception));
            }
        }
        failure = close(platform.output(), failure);
        failure = close(platform.input(), failure);
        if (failure != null) {
            throw failure;
        }
    }

    interface Platform {
        String state() throws IOException;

        void raw() throws IOException;

        void restore(String state) throws IOException;

        TerminalSize size() throws IOException;

        InputStream input();

        OutputStream output();
    }

    interface Hooks {
        void add(Thread hook);

        void remove(Thread hook);
    }

    private static void closeStreams(Platform platform, Throwable failure) {
        for (AutoCloseable stream : List.of(platform.output(), platform.input())) {
            try {
                stream.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static IOException close(AutoCloseable closeable, IOException failure) {
        try {
            closeable.close();
        } catch (Exception exception) {
            IOException closeFailure = exception instanceof IOException io
                    ? io : new IOException(exception);
            return addFailure(failure, closeFailure);
        }
        return failure;
    }

    private static IOException addFailure(IOException failure, IOException addition) {
        if (failure == null) {
            return addition;
        }
        failure.addSuppressed(addition);
        return failure;
    }

    private static final class RuntimeHooks implements Hooks {
        @Override
        public void add(Thread hook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }

        @Override
        public void remove(Thread hook) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    private static final class NativePlatform implements Platform {
        private static final File TTY = new File("/dev/tty");
        private final InputStream input;
        private final OutputStream output;

        private NativePlatform() throws IOException {
            input = Channels.newInputStream(FileChannel.open(TTY.toPath(), StandardOpenOption.READ));
            try {
                output = new FileOutputStream(TTY);
            } catch (IOException failure) {
                input.close();
                throw failure;
            }
        }

        @Override
        public String state() throws IOException {
            return stty("-g");
        }

        @Override
        public void raw() throws IOException {
            stty("raw", "-echo");
        }

        @Override
        public void restore(String state) throws IOException {
            stty(state);
        }

        @Override
        public TerminalSize size() throws IOException {
            String[] fields = stty("size").split("\\s+");
            if (fields.length != 2) {
                throw new IOException("stty returned an invalid terminal size");
            }
            try {
                return new TerminalSize(Integer.parseInt(fields[1]), Integer.parseInt(fields[0]));
            } catch (IllegalArgumentException error) {
                throw new IOException("stty returned an invalid terminal size", error);
            }
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        private static String stty(String... arguments) throws IOException {
            List<String> command = new ArrayList<>(arguments.length + 1);
            command.add("stty");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .redirectInput(TTY)
                    .redirectErrorStream(true)
                    .start();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("stty timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("interrupted while running stty", exception);
            }
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new IOException(result.isEmpty() ? "stty failed" : result);
            }
            return result;
        }
    }
}
