package pro.deta.orion.agentd.runtime;

import pro.deta.orion.agentd.session.ControlHostProbe;
import pro.deta.orion.agentd.session.FileSystemJournalProbe;
import pro.deta.orion.agentd.session.HostObservation;
import pro.deta.orion.agentd.session.JournalObservation;
import pro.deta.orion.agentd.session.JournalProbe;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.OperationDeadline;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionManifest;
import pro.deta.orion.agentd.session.SessionManifestReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class NativeRuntime implements SessionRuntime {
    private final Path executable;
    private final Path sessionsDirectory;
    private final WorkspaceResolver workspaceResolver;
    private final DetachedProcessLauncher launcher;
    private final SessionManifestReader manifestReader;
    private final JournalProbe journalProbe;
    private final SessionHandoffProbe handoffProbe;
    private final Duration initializationTimeout;
    private final Duration cleanupTimeout;
    private final Duration pollInterval;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    public NativeRuntime(
            Path executable,
            Path sessionsDirectory,
            Duration initializationTimeout,
            Duration controlTimeout,
            Duration cleanupTimeout
    ) {
        this(
                executable,
                sessionsDirectory,
                new ExistingDirectoryWorkspaceResolver(),
                DetachedProcessLauncher.processBuilder(),
                new JsonSessionManifestReader(),
                new FileSystemJournalProbe(),
                new ControlHostProbe(new SessionControlClient(controlTimeout))::probe,
                initializationTimeout,
                cleanupTimeout,
                Duration.ofMillis(10),
                System::nanoTime,
                NativeRuntime::sleep);
    }

    public NativeRuntime(
            Path executable,
            Path sessionsDirectory,
            WorkspaceResolver workspaceResolver,
            DetachedProcessLauncher launcher,
            SessionManifestReader manifestReader,
            JournalProbe journalProbe,
            SessionHandoffProbe handoffProbe,
            Duration initializationTimeout,
            Duration cleanupTimeout,
            Duration pollInterval
    ) {
        this(
                executable,
                sessionsDirectory,
                workspaceResolver,
                launcher,
                manifestReader,
                journalProbe,
                handoffProbe,
                initializationTimeout,
                cleanupTimeout,
                pollInterval,
                System::nanoTime,
                NativeRuntime::sleep);
    }

    NativeRuntime(
            Path executable,
            Path sessionsDirectory,
            WorkspaceResolver workspaceResolver,
            DetachedProcessLauncher launcher,
            SessionManifestReader manifestReader,
            JournalProbe journalProbe,
            SessionHandoffProbe handoffProbe,
            Duration initializationTimeout,
            Duration cleanupTimeout,
            Duration pollInterval,
            LongSupplier nanoTime,
            Sleeper sleeper
    ) {
        this.executable = normalize(executable, "executable");
        this.sessionsDirectory = normalize(sessionsDirectory, "sessionsDirectory");
        this.workspaceResolver = Objects.requireNonNull(workspaceResolver, "workspaceResolver");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.manifestReader = Objects.requireNonNull(manifestReader, "manifestReader");
        this.journalProbe = Objects.requireNonNull(journalProbe, "journalProbe");
        this.handoffProbe = Objects.requireNonNull(handoffProbe, "handoffProbe");
        this.initializationTimeout = positive(initializationTimeout, "initializationTimeout");
        this.cleanupTimeout = positive(cleanupTimeout, "cleanupTimeout");
        this.pollInterval = positive(pollInterval, "pollInterval");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public SessionLaunchResult launch(SessionSpec spec) {
        Objects.requireNonNull(spec, "spec");
        SessionLaunchResult validation = validate(spec);
        if (validation != null) {
            return validation;
        }
        WorkspaceResolver.Resolution resolution = workspaceResolver.resolve(spec.workspace());
        if (resolution instanceof WorkspaceResolver.Resolution.Failed failed) {
            return SessionLaunchResult.failed(failed.kind(), failed.detail());
        }
        Path workingDirectory = ((WorkspaceResolver.Resolution.Resolved) resolution).workingDirectory();
        Path sessionDirectory = sessionsDirectory.resolve(spec.sessionId().value()).normalize();
        if (!sessionDirectory.startsWith(sessionsDirectory) || sessionDirectory.equals(sessionsDirectory)) {
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.INVALID_SPEC, "session ID escapes the sessions directory");
        }
        try {
            Files.createDirectories(sessionsDirectory);
            Files.createDirectory(sessionDirectory);
        } catch (FileAlreadyExistsException error) {
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.SESSION_EXISTS, "session directory already exists");
        } catch (IOException error) {
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.LAUNCH_FAILED, error.getMessage());
        }

        DetachedProcessLauncher.TentativeProcess process;
        try {
            process = launcher.launch(command(spec, workingDirectory, sessionDirectory),
                    sessionDirectory.resolve("session-host.log"));
        } catch (IOException | RuntimeException error) {
            return cleanupWithoutProcess(sessionDirectory, error);
        }
        return awaitHandoff(spec, sessionDirectory, process);
    }

    private SessionLaunchResult awaitHandoff(
            SessionSpec spec,
            Path sessionDirectory,
            DetachedProcessLauncher.TentativeProcess process
    ) {
        OperationDeadline deadline = OperationDeadline.after(initializationTimeout, nanoTime);
        String lastDetail = "session host has not initialized";
        while (!deadline.expired()) {
            if (!process.isAlive()) {
                return cleanup(
                        sessionDirectory,
                        process,
                        SessionLaunchResult.FailureKind.INITIALIZATION_FAILED,
                        "session host exited before durable handoff");
            }
            try {
                SessionManifest manifest = manifestReader.read(sessionDirectory);
                if (manifest.hostPid() != process.pid()) {
                    lastDetail = "manifest host PID does not match the launched process";
                } else if (journalProbe.probe(sessionDirectory) != JournalObservation.READABLE) {
                    lastDetail = "session journal is not readable";
                } else {
                    HostObservation host = handoffProbe.probe(sessionDirectory, manifest, deadline);
                    if (host.status() == HostObservation.Status.LIVE && !deadline.expired()) {
                        return new SessionLaunchResult.Started(spec.sessionId(), sessionDirectory);
                    }
                    lastDetail = "session host STATUS is not live";
                }
            } catch (IOException | RuntimeException error) {
                lastDetail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
            long remaining = deadline.remainingNanos();
            if (remaining == 0) {
                break;
            }
            Duration wait = Duration.ofNanos(Math.min(pollInterval.toNanos(), remaining));
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return cleanup(
                        sessionDirectory,
                        process,
                        SessionLaunchResult.FailureKind.INITIALIZATION_FAILED,
                        "interrupted while waiting for session host initialization");
            }
        }
        return cleanup(
                sessionDirectory,
                process,
                SessionLaunchResult.FailureKind.INITIALIZATION_TIMEOUT,
                "session host initialization timed out: " + lastDetail);
    }

    private SessionLaunchResult validate(SessionSpec spec) {
        if (!nativeSessionId(spec.sessionId().value())) {
            return invalid("session ID is not accepted by the native session-host CLI");
        }
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            return invalid("session-host executable is not an executable file");
        }
        if (!spec.environment().isEmpty()) {
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.UNSUPPORTED_ENVIRONMENT,
                    "native session-host does not accept arbitrary environment entries");
        }
        if (spec.command().isEmpty() || spec.command().getFirst().isBlank()) {
            return invalid("child executable is blank");
        }
        for (String argument : spec.command()) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                return invalid("child command contains an invalid argument");
            }
        }
        if (!dimension(spec.columns()) || !dimension(spec.rows())) {
            return invalid("terminal dimensions must be between 1 and 65535");
        }
        if (!environmentValue(spec.terminalType())
                || spec.colorTerminal().filter(value -> !environmentValue(value)).isPresent()) {
            return invalid("terminal environment value is invalid");
        }
        if (spec.sandbox().policy().isPresent()) {
            Path policy = spec.sandbox().policy().orElseThrow();
            if (!Files.isRegularFile(policy) || !Files.isReadable(policy)) {
                return invalid("sandbox policy is not a readable file");
            }
        }
        return null;
    }

    private List<String> command(SessionSpec spec, Path workingDirectory, Path sessionDirectory) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        option(command, "--session-id", spec.sessionId().value());
        option(command, "--session-dir", sessionDirectory.toString());
        option(command, "--cwd", workingDirectory.toString());
        option(command, "--cols", Integer.toString(spec.columns()));
        option(command, "--rows", Integer.toString(spec.rows()));
        option(command, "--term", spec.terminalType());
        spec.colorTerminal().ifPresent(value -> option(command, "--colorterm", value));
        spec.sandbox().policy().ifPresent(policy -> option(command, "--sandbox-policy", policy.toString()));
        if (spec.sandbox().policy().isPresent()) {
            String unavailable = spec.sandbox().unavailable() == SessionSpec.Unavailable.FAIL
                    ? "fail"
                    : "run-unsandboxed";
            option(command, "--sandbox-unavailable", unavailable);
        }
        command.add("--");
        command.addAll(spec.command());
        return List.copyOf(command);
    }

    private SessionLaunchResult cleanupWithoutProcess(Path directory, Exception launchFailure) {
        try {
            deleteTree(directory);
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.LAUNCH_FAILED,
                    detail(launchFailure));
        } catch (IOException cleanupFailure) {
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.CLEANUP_FAILED,
                    detail(launchFailure) + "; cleanup failed: " + detail(cleanupFailure));
        }
    }

    private SessionLaunchResult cleanup(
            Path directory,
            DetachedProcessLauncher.TentativeProcess process,
            SessionLaunchResult.FailureKind originalKind,
            String originalDetail
    ) {
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(cleanupTimeout)) {
                    process.destroyForcibly();
                    if (!process.waitFor(cleanupTimeout)) {
                        return SessionLaunchResult.failed(
                                SessionLaunchResult.FailureKind.CLEANUP_FAILED,
                                originalDetail + "; launched process exit could not be confirmed");
                    }
                }
            }
            if (process.isAlive()) {
                return SessionLaunchResult.failed(
                        SessionLaunchResult.FailureKind.CLEANUP_FAILED,
                        originalDetail + "; launched process remains alive");
            }
            deleteTree(directory);
            return SessionLaunchResult.failed(originalKind, originalDetail);
        } catch (IOException | RuntimeException error) {
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.CLEANUP_FAILED,
                    originalDetail + "; cleanup failed: " + detail(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return SessionLaunchResult.failed(
                    SessionLaunchResult.FailureKind.CLEANUP_FAILED,
                    originalDetail + "; cleanup was interrupted before exit was confirmed");
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(visited);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void option(List<String> command, String name, String value) {
        command.add(name);
        command.add(value);
    }

    private static boolean dimension(int value) {
        return value >= 1 && value <= 0xffff;
    }

    private static boolean nativeSessionId(String value) {
        if (value.isEmpty() || value.length() > 128 || value.equals(".") || value.equals("..")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isNativeSessionIdCharacter(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNativeSessionIdCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '.'
                || character == '_'
                || character == '-';
    }

    private static boolean environmentValue(String value) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 1 && bytes <= 128 && value.indexOf('=') < 0 && value.indexOf('\0') < 0;
    }

    private static SessionLaunchResult invalid(String detail) {
        return SessionLaunchResult.failed(SessionLaunchResult.FailureKind.INVALID_SPEC, detail);
    }

    private static String detail(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " cannot be represented in nanoseconds", error);
        }
        return duration;
    }

    private static void sleep(Duration duration) throws InterruptedException {
        long millis = duration.toMillis();
        int nanos = duration.minusMillis(millis).getNano();
        Thread.sleep(millis, nanos);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
