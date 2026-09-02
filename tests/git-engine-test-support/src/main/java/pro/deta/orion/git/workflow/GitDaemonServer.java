package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class GitDaemonServer implements GitServer {
    private static final int MAX_START_ATTEMPTS = 5;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FORCE_STOP_TIMEOUT = Duration.ofSeconds(2);

    private final GitCommandRunner commands;
    private final PortSupplier ports;
    private Process daemon;
    private Path root;
    private Path logFile;
    private int port;
    private String version;

    GitDaemonServer(String executable) {
        this(executable, GitDaemonServer::availableLoopbackPort);
    }

    GitDaemonServer(String executable, PortSupplier ports) {
        commands = new GitCommandRunner(executable, COMMAND_TIMEOUT);
        this.ports = ports;
    }

    @Override
    public String name() {
        return "git";
    }

    @Override
    public Set<GitCapability> capabilities() {
        return GitCapability.all();
    }

    @Override
    public synchronized GitRemoteRepository createRemoteRepository(
            Path directory,
            String repositoryName) throws Exception {
        requireRepositoryName(repositoryName);
        Path requestedRoot = directory.toAbsolutePath().normalize();
        if (root != null && !root.equals(requestedRoot)) {
            throw new IllegalArgumentException("Git daemon repositories must share one isolated root");
        }
        requireCanonicalGit();
        Files.createDirectories(requestedRoot);
        Path repositoryPath = requestedRoot.resolve(repositoryName).normalize();
        commands.run(null,
                "init",
                "--bare",
                "--initial-branch=" + GitScenarioContext.DEFAULT_BRANCH,
                repositoryPath.toString());
        if (daemon == null) {
            root = requestedRoot;
            logFile = root.resolve(".orion-git-daemon.log");
            startDaemon();
        }
        return new GitRemoteRepository(
                repositoryPath,
                "git://127.0.0.1:" + port + "/" + repositoryName);
    }

    @Override
    public synchronized String diagnostics() {
        StringBuilder result = new StringBuilder(version == null ? unavailableVersion() : version);
        if (port > 0) {
            result.append("; daemon=127.0.0.1:").append(port);
        }
        String log = readLog();
        if (!log.isBlank()) {
            result.append(System.lineSeparator()).append(log.stripTrailing());
        }
        return result.toString();
    }

    @Override
    public synchronized void close() throws IOException {
        if (daemon == null) {
            return;
        }
        Process running = daemon;
        daemon = null;
        running.destroy();
        if (!waitFor(running, STOP_TIMEOUT)) {
            running.destroyForcibly();
            if (!waitFor(running, FORCE_STOP_TIMEOUT)) {
                throw new IOException("Canonical git daemon did not terminate after forced shutdown: "
                        + diagnostics());
            }
        }
    }

    private void requireCanonicalGit() throws IOException {
        if (version != null) {
            return;
        }
        try {
            version = commands.version();
        } catch (IOException error) {
            throw new IOException("Canonical Git prerequisite is unavailable for git daemon: "
                    + error.getMessage(), error);
        }
    }

    private void startDaemon() throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            int candidate = ports.get();
            String marker = "start attempt " + attempt + " on 127.0.0.1:" + candidate;
            appendLog(marker + System.lineSeparator());
            Process process = startProcess(candidate);
            try {
                awaitReady(process, marker);
                daemon = process;
                port = candidate;
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                stopFailedAttempt(process);
                appendLog("attempt " + attempt + " failed: " + failure.getMessage()
                        + System.lineSeparator());
            }
        }
        throw new IOException("Canonical git daemon failed to bind or start after "
                + MAX_START_ATTEMPTS + " dynamic-port attempts: " + readLog(), lastFailure);
    }

    private Process startProcess(int candidate) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(commands.command(
                "daemon",
                "--verbose",
                "--export-all",
                "--enable=receive-pack",
                "--base-path=" + root,
                "--listen=127.0.0.1",
                "--port=" + candidate,
                root.toString()));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.environment().putAll(commands.baseEnvironment());
        try {
            return builder.start();
        } catch (IOException error) {
            throw new IOException("Unable to start canonical git daemon using " + version
                    + ": " + error.getMessage(), error);
        }
    }

    private void awaitReady(Process process, String marker) throws IOException {
        Instant deadline = Instant.now().plus(START_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IOException("git daemon exited with " + process.exitValue());
            }
            String log = readLog();
            int attemptStart = log.lastIndexOf(marker);
            if (attemptStart >= 0 && log.substring(attemptStart).contains("Ready to rumble")) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for canonical git daemon", error);
            }
        }
        throw new IOException("timed out waiting " + START_TIMEOUT + " for git daemon readiness");
    }

    private static int availableLoopbackPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return reservation.getLocalPort();
        }
    }

    private static void stopFailedAttempt(Process process) throws IOException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (!waitFor(process, STOP_TIMEOUT)) {
            process.destroyForcibly();
            if (!waitFor(process, FORCE_STOP_TIMEOUT)) {
                throw new IOException("Failed git daemon attempt did not terminate");
            }
        }
    }

    private static boolean waitFor(Process process, Duration timeout) throws IOException {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for canonical git daemon", error);
        }
    }

    private void appendLog(String message) throws IOException {
        Files.writeString(
                logFile,
                message,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private String readLog() {
        if (logFile == null || !Files.exists(logFile)) {
            return "";
        }
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "unable to read daemon log: " + error.getMessage();
        }
    }

    private String unavailableVersion() {
        try {
            return commands.version();
        } catch (IOException error) {
            return "canonical Git unavailable: " + error.getMessage();
        }
    }

    private static void requireRepositoryName(String repositoryName) {
        if (repositoryName.isBlank()
                || ".".equals(repositoryName)
                || "..".equals(repositoryName)
                || repositoryName.contains("/")
                || repositoryName.contains("\\")) {
            throw new IllegalArgumentException("Repository name must be one path segment: " + repositoryName);
        }
    }

    @FunctionalInterface
    interface PortSupplier {
        int get() throws IOException;
    }
}
