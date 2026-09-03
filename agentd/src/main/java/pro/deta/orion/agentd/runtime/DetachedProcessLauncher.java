package pro.deta.orion.agentd.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface DetachedProcessLauncher {
    TentativeProcess launch(List<String> command, Path logFile) throws IOException;

    static DetachedProcessLauncher processBuilder() {
        return (command, logFile) -> {
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            File nullInput = new File(windows ? "NUL" : "/dev/null");
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(nullInput))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectErrorStream(true)
                    .start();
            return new JavaProcess(process);
        };
    }

    interface TentativeProcess {
        long pid();

        boolean isAlive();

        void destroy();

        void destroyForcibly();

        boolean waitFor(Duration timeout) throws InterruptedException;
    }

    final class JavaProcess implements TentativeProcess {
        private final Process process;

        private JavaProcess(Process process) {
            this.process = process;
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void destroy() {
            process.destroy();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
