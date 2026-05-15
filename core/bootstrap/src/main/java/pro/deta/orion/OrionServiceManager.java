package pro.deta.orion;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class OrionServiceManager {
    private static final String DEFAULT_APP_NAME = "orion";
    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(30);

    private final Settings settings;
    private final ProcessLauncher processLauncher;
    private final ProcessLookup processLookup;

    OrionServiceManager(Settings settings, ProcessLauncher processLauncher, ProcessLookup processLookup) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.processLauncher = Objects.requireNonNull(processLauncher, "processLauncher");
        this.processLookup = Objects.requireNonNull(processLookup, "processLookup");
    }

    static OrionServiceManager systemDefault() {
        Settings settings = settingsFrom(System.getenv(), currentArtifactPath());
        return new OrionServiceManager(settings, new ProcessBuilderLauncher(settings), new ProcessHandleLookup());
    }

    int start(List<String> applicationArguments, PrintStream output, PrintStream errors) throws IOException {
        Optional<Long> existingPid = readPid();
        if (existingPid.isPresent()) {
            Optional<ManagedProcess> existingProcess = runningProcess(existingPid.get());
            if (existingProcess.isPresent()) {
                output.println(settings.appName() + " is already running with PID " + existingPid.get());
                return 0;
            }
            Files.deleteIfExists(settings.pidFile());
        }

        createRuntimeDirectories();
        ManagedProcess process = processLauncher.start(commandFor(applicationArguments));
        Files.writeString(
                settings.pidFile(),
                process.pid() + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        output.println(settings.appName() + " started with PID " + process.pid());
        return 0;
    }

    int stop(PrintStream output, PrintStream errors) throws IOException {
        Optional<Long> pid = readPid();
        if (pid.isEmpty()) {
            Files.deleteIfExists(settings.pidFile());
            output.println(settings.appName() + " is not running");
            return 0;
        }

        Optional<ManagedProcess> process = runningProcess(pid.get());
        if (process.isEmpty()) {
            Files.deleteIfExists(settings.pidFile());
            output.println(settings.appName() + " is not running");
            return 0;
        }

        ManagedProcess managedProcess = process.get();
        managedProcess.destroy();
        if (managedProcess.isAlive() && !managedProcess.waitFor(settings.stopTimeout())) {
            managedProcess.destroyForcibly();
            managedProcess.waitFor(Duration.ofSeconds(5));
        }

        Files.deleteIfExists(settings.pidFile());
        output.println(settings.appName() + " stopped");
        return 0;
    }

    int status(PrintStream output, PrintStream errors) {
        Optional<Long> pid = readPidSilently();
        if (pid.isPresent() && runningProcess(pid.get()).isPresent()) {
            output.println(settings.appName() + " is running with PID " + pid.get());
            return 0;
        }

        output.println(settings.appName() + " is not running");
        return 3;
    }

    int restart(List<String> applicationArguments, PrintStream output, PrintStream errors) throws IOException {
        int stopExitCode = stop(output, errors);
        if (stopExitCode != 0) {
            return stopExitCode;
        }
        return start(applicationArguments, output, errors);
    }

    private Optional<ManagedProcess> runningProcess(long pid) {
        Optional<ManagedProcess> process = processLookup.find(pid);
        if (process.isEmpty() || !process.get().isAlive()) {
            return Optional.empty();
        }
        return process;
    }

    private List<String> commandFor(List<String> applicationArguments) {
        List<String> command = new ArrayList<>();
        command.add(settings.javaCommand());
        command.addAll(splitCommandLine(settings.javaOptions()));
        command.add("-jar");
        command.add(settings.artifact().toString());
        command.add("run");
        command.addAll(applicationArguments);
        return List.copyOf(command);
    }

    private void createRuntimeDirectories() throws IOException {
        Files.createDirectories(settings.home());
        createParentDirectory(settings.pidFile());
        createParentDirectory(settings.logFile());
    }

    private static void createParentDirectory(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Optional<Long> readPid() throws IOException {
        if (!Files.isRegularFile(settings.pidFile())) {
            return Optional.empty();
        }

        String value = Files.readString(settings.pidFile(), StandardCharsets.UTF_8).trim();
        if (value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Long> readPidSilently() {
        try {
            return readPid();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Settings settingsFrom(Map<String, String> environment, Path artifact) {
        String appName = valueOrDefault(environment.get("APP_NAME"), DEFAULT_APP_NAME);
        String javaCommand = valueOrDefault(environment.get("JAVA_CMD"), "java");
        String javaOptions = valueOrDefault(environment.get("JAVA_OPTS"), "");
        Duration stopTimeout = stopTimeout(environment.get("ORION_STOP_TIMEOUT"));

        Path normalizedArtifact = artifact.toAbsolutePath().normalize();
        Path home = optionalPath(environment.get("ORION_HOME"))
                .orElseGet(() -> defaultHome(normalizedArtifact));
        Path pidFile = optionalPath(environment.get("ORION_PID_FILE"))
                .orElse(home.resolve(appName + ".pid"));
        Path logFile = optionalPath(environment.get("ORION_LOG_FILE"))
                .orElse(home.resolve(appName + ".log"));

        return new Settings(appName, home, pidFile, logFile, stopTimeout, javaCommand, javaOptions, normalizedArtifact);
    }

    private static Duration stopTimeout(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return DEFAULT_STOP_TIMEOUT;
        }
        return Duration.ofSeconds(Long.parseLong(normalized));
    }

    private static Path defaultHome(Path artifact) {
        Path parent = artifact.getParent();
        if (parent != null) {
            return parent;
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Optional<Path> optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? Optional.empty() : Optional.of(Path.of(normalized));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Path currentArtifactPath() {
        try {
            return Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate current Orion artifact", e);
        }
    }

    private static List<String> splitCommandLine(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean tokenStarted = false;
        boolean escaping = false;
        char quote = 0;

        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (escaping) {
                current.append(next);
                tokenStarted = true;
                escaping = false;
                continue;
            }
            if (next == '\\' && quote != '\'') {
                escaping = true;
                tokenStarted = true;
                continue;
            }
            if (quote != 0) {
                if (next == quote) {
                    quote = 0;
                } else {
                    current.append(next);
                }
                tokenStarted = true;
                continue;
            }
            if (next == '\'' || next == '"') {
                quote = next;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(next)) {
                if (tokenStarted) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }

            current.append(next);
            tokenStarted = true;
        }

        if (escaping) {
            current.append('\\');
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unterminated quote in JAVA_OPTS");
        }
        if (tokenStarted) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }

    record Settings(
            String appName,
            Path home,
            Path pidFile,
            Path logFile,
            Duration stopTimeout,
            String javaCommand,
            String javaOptions,
            Path artifact
    ) {
        Settings {
            Objects.requireNonNull(appName, "appName");
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(pidFile, "pidFile");
            Objects.requireNonNull(logFile, "logFile");
            Objects.requireNonNull(stopTimeout, "stopTimeout");
            Objects.requireNonNull(javaCommand, "javaCommand");
            Objects.requireNonNull(javaOptions, "javaOptions");
            Objects.requireNonNull(artifact, "artifact");
        }
    }

    interface ProcessLauncher {
        ManagedProcess start(List<String> command) throws IOException;
    }

    interface ProcessLookup {
        Optional<ManagedProcess> find(long pid);
    }

    interface ManagedProcess {
        long pid();

        boolean isAlive();

        boolean destroy();

        boolean destroyForcibly();

        boolean waitFor(Duration timeout);
    }

    private static final class ProcessBuilderLauncher implements ProcessLauncher {
        private final Settings settings;

        private ProcessBuilderLauncher(Settings settings) {
            this.settings = settings;
        }

        @Override
        public ManagedProcess start(List<String> command) throws IOException {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(settings.home().toFile());
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(settings.logFile().toFile()));
            processBuilder.redirectErrorStream(true);
            return new JavaProcess(processBuilder.start());
        }
    }

    private static final class ProcessHandleLookup implements ProcessLookup {
        @Override
        public Optional<ManagedProcess> find(long pid) {
            return ProcessHandle.of(pid).map(ProcessHandleProcess::new);
        }
    }

    private static final class JavaProcess implements ManagedProcess {
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
        public boolean destroy() {
            process.destroy();
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            process.destroyForcibly();
            return true;
        }

        @Override
        public boolean waitFor(Duration timeout) {
            try {
                return process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static final class ProcessHandleProcess implements ManagedProcess {
        private final ProcessHandle processHandle;

        private ProcessHandleProcess(ProcessHandle processHandle) {
            this.processHandle = processHandle;
        }

        @Override
        public long pid() {
            return processHandle.pid();
        }

        @Override
        public boolean isAlive() {
            return processHandle.isAlive();
        }

        @Override
        public boolean destroy() {
            return processHandle.destroy();
        }

        @Override
        public boolean destroyForcibly() {
            return processHandle.destroyForcibly();
        }

        @Override
        public boolean waitFor(Duration timeout) {
            try {
                processHandle.onExit().get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException e) {
                return !processHandle.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                return !processHandle.isAlive();
            }
        }
    }
}
