package pro.deta.orion.agentd.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agentd.session.ChildState;
import pro.deta.orion.agentd.session.ControlEndpoint;
import pro.deta.orion.agentd.session.HostObservation;
import pro.deta.orion.agentd.session.JournalObservation;
import pro.deta.orion.agentd.session.OperationDeadline;
import pro.deta.orion.agentd.session.SessionManifest;
import pro.deta.orion.agentd.sandbox.SourcePolicyParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NativeRuntimeTest {
    @TempDir
    Path temporaryDirectory;
    private Path executable;
    private Path sessions;
    private FakeProcess process;
    private CapturingLauncher launcher;

    @BeforeEach
    void prepare() throws IOException {
        executable = Files.writeString(temporaryDirectory.resolve("session-host"), "fixture");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        sessions = temporaryDirectory.resolve("sessions");
        process = new FakeProcess(4242, true, true);
        launcher = new CapturingLauncher(process);
    }

    @Test
    void handsOffOnlyAfterManifestJournalAndStatusAreReady() {
        AtomicReference<OperationDeadline> observedDeadline = new AtomicReference<>();
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> {
                    observedDeadline.set(deadline);
                    return HostObservation.live(ChildState.LIVE);
                });

        SessionLaunchResult result = runtime.launch(spec());

        Path sessionDirectory = sessions.resolve("session-1").toAbsolutePath().normalize();
        assertThat(result).isEqualTo(
                new SessionLaunchResult.Started(new SessionId("session-1"), sessionDirectory));
        assertThat(sessionDirectory).isDirectory();
        assertThat(process.destroyCalls).isZero();
        assertThat(process.forceCalls).isZero();
        assertThat(observedDeadline.get()).isNotNull();
        assertThat(launcher.logFile).isEqualTo(sessionDirectory.resolve("session-host.log"));
        assertThat(launcher.command).containsSequence(
                executable.toString(),
                "--session-id", "session-1",
                "--start-command-id", "command.start",
                "--session-dir", sessionDirectory.toString(),
                "--cwd", temporaryDirectory.toAbsolutePath().normalize().toString());
        assertThat(launcher.command).endsWith("--", "sh", "-l");
    }

    @Test
    void waitsForAReadableJournalInsteadOfTreatingMetadataAsReadiness() {
        AtomicInteger probes = new AtomicInteger();
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> probes.incrementAndGet() < 2
                        ? JournalObservation.MISSING
                        : JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        SessionLaunchResult result = runtime.launch(spec());

        assertThat(result).isInstanceOf(SessionLaunchResult.Started.class);
        assertThat(probes).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsUnsupportedEnvironmentBeforeCreatingTheSessionDirectory() {
        SessionSpec withEnvironment = new SessionSpec(
                new SessionId("session-1"),
                spec().startCommandId(),
                List.of("sh"),
                new WorkspaceReference.ExistingDirectory(temporaryDirectory),
                Map.of("SECRET", "value"),
                80,
                24,
                "xterm-256color",
                Optional.empty(),
                SessionSpec.Sandbox.none());
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        SessionLaunchResult result = runtime.launch(withEnvironment);

        assertFailure(result, SessionLaunchResult.FailureKind.UNSUPPORTED_ENVIRONMENT);
        assertThat(sessions.resolve("session-1")).doesNotExist();
        assertThat(launcher.command).isEmpty();
    }

    @Test
    void productionAssemblyUsesTheExistingNativeBoundaries() {
        NativeRuntime runtime = new NativeRuntime(
                executable,
                sessions,
                Duration.ofMillis(50),
                Duration.ofMillis(20),
                Duration.ofMillis(10));
        SessionSpec unsupported = new SessionSpec(
                spec().sessionId(),
                spec().startCommandId(),
                spec().command(),
                spec().workspace(),
                Map.of("UNSUPPORTED", "value"),
                spec().columns(),
                spec().rows(),
                spec().terminalType(),
                spec().colorTerminal(),
                spec().sandbox());

        SessionLaunchResult result = runtime.launch(unsupported);

        assertFailure(result, SessionLaunchResult.FailureKind.UNSUPPORTED_ENVIRONMENT);
    }

    @Test
    void rejectsBlankExecutableBeforeCreatingTheSessionDirectory() {
        SessionSpec blankExecutable = new SessionSpec(
                spec().sessionId(),
                spec().startCommandId(),
                List.of("  ", "empty arguments after the executable remain valid", ""),
                spec().workspace(),
                spec().environment(),
                spec().columns(),
                spec().rows(),
                spec().terminalType(),
                spec().colorTerminal(),
                spec().sandbox());
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        SessionLaunchResult result = runtime.launch(blankExecutable);

        assertFailure(result, SessionLaunchResult.FailureKind.INVALID_SPEC);
        assertThat(sessions.resolve("session-1")).doesNotExist();
        assertThat(launcher.command).isEmpty();
    }

    @Test
    void permitsEmptyArgumentsAfterTheExecutable() {
        SessionSpec emptyArgument = new SessionSpec(
                spec().sessionId(),
                spec().startCommandId(),
                List.of("sh", ""),
                spec().workspace(),
                spec().environment(),
                spec().columns(),
                spec().rows(),
                spec().terminalType(),
                spec().colorTerminal(),
                spec().sandbox());
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        SessionLaunchResult result = runtime.launch(emptyArgument);

        assertThat(result).isInstanceOf(SessionLaunchResult.Started.class);
        assertThat(launcher.command).endsWith("--", "sh", "");
    }

    @Test
    void rejectsInvalidSandboxPolicyAndExistingSessionWithoutLaunching() throws IOException {
        SessionSpec invalidPolicy = new SessionSpec(
                new SessionId("session-1"),
                spec().startCommandId(),
                List.of("sh"),
                new WorkspaceReference.ExistingDirectory(temporaryDirectory),
                Map.of(),
                80,
                24,
                "xterm-256color",
                Optional.empty(),
                new SessionSpec.Sandbox(
                        Optional.of(temporaryDirectory.resolve("missing-policy"))));
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        assertFailure(runtime.launch(invalidPolicy), SessionLaunchResult.FailureKind.INVALID_SPEC);
        Files.createDirectories(sessions.resolve("session-1"));
        assertFailure(runtime.launch(spec()), SessionLaunchResult.FailureKind.SESSION_EXISTS);
        assertThat(launcher.command).isEmpty();
    }

    @Test
    void compilesSandboxPolicyIntoSessionAndPassesOnlyGeneratedPath() throws IOException {
        Path source = Files.writeString(temporaryDirectory.resolve("policy.landlock"), """
                landlock 1
                ro "%s"
                """.formatted(temporaryDirectory.toRealPath()));
        SessionSpec sandboxed = new SessionSpec(
                spec().sessionId(), spec().startCommandId(), spec().command(), spec().workspace(),
                spec().environment(),
                spec().columns(), spec().rows(), spec().terminalType(), spec().colorTerminal(),
                new SessionSpec.Sandbox(Optional.of(source)));
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        assertThat(runtime.launch(sandboxed)).isInstanceOf(SessionLaunchResult.Started.class);

        Path compiled = sessions.resolve("session-1/sandbox-policy.cbor").toAbsolutePath().normalize();
        assertThat(compiled).isRegularFile();
        assertThat(launcher.command).containsSequence("--sandbox-policy", compiled.toString());
        assertThat(launcher.command).doesNotContain(source.toString());
    }

    @Test
    void rejectsMalformedOrInexactPolicyBeforeSessionCreation() throws IOException {
        Path malformed = Files.writeString(temporaryDirectory.resolve("malformed.landlock"), "ro \"/\"");
        SessionSpec malformedSpec = withPolicy(malformed);
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        assertFailure(runtime.launch(malformedSpec), SessionLaunchResult.FailureKind.INVALID_SPEC);
        assertThat(sessions.resolve("session-1")).doesNotExist();
        assertThat(launcher.command).isEmpty();

        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("exactness"));
        Path denied = Files.createDirectory(root.resolve("denied"));
        Path inexact = Files.writeString(temporaryDirectory.resolve("inexact.landlock"), """
                landlock 1
                read-dir "%s"
                none "%s"
                """.formatted(root, denied));
        assertFailure(runtime.launch(withPolicy(inexact)), SessionLaunchResult.FailureKind.INVALID_SPEC);
        assertThat(sessions.resolve("session-1")).doesNotExist();
        assertThat(launcher.command).isEmpty();
    }

    @Test
    void rejectsOversizedPolicyBeforeSessionCreation() throws IOException {
        Path oversized = temporaryDirectory.resolve("oversized.landlock");
        Files.write(oversized, new byte[SourcePolicyParser.MAX_SOURCE_BYTES + 1]);
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        assertFailure(runtime.launch(withPolicy(oversized)), SessionLaunchResult.FailureKind.INVALID_SPEC);
        assertThat(sessions.resolve("session-1")).doesNotExist();
        assertThat(launcher.command).isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsSymbolicLinkPolicyBeforeSessionCreation() throws IOException {
        Path target = Files.writeString(temporaryDirectory.resolve("target.landlock"), "landlock 1\n");
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("linked.landlock"), target);
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        assertFailure(runtime.launch(withPolicy(link)), SessionLaunchResult.FailureKind.INVALID_SPEC);
        assertThat(sessions.resolve("session-1")).doesNotExist();
        assertThat(launcher.command).isEmpty();
    }

    @Test
    void rejectsAgentProtocolSessionIdOutsideTheNativeCliSubsetBeforeMutation() {
        SessionSpec invalidNativeId = new SessionSpec(
                new SessionId("session:1"),
                spec().startCommandId(),
                spec().command(),
                spec().workspace(),
                Map.of(),
                spec().columns(),
                spec().rows(),
                spec().terminalType(),
                spec().colorTerminal(),
                spec().sandbox());
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        SessionLaunchResult result = runtime.launch(invalidNativeId);

        assertFailure(result, SessionLaunchResult.FailureKind.INVALID_SPEC);
        assertThat(sessions.resolve("session:1")).doesNotExist();
        assertThat(launcher.command).isEmpty();
    }

    @Test
    void rejectsStatusForAProcessOtherThanTheOneThisAttemptLaunched() {
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 9999),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.live(ChildState.LIVE));

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.INITIALIZATION_TIMEOUT);
        assertThat(process.destroyCalls).isEqualTo(1);
        assertThat(sessions.resolve("session-1")).isDirectory();
    }

    @Test
    void removesOnlyItsDirectoryAfterAnEarlyHostExit() {
        process.alive = false;
        NativeRuntime runtime = runtime(
                directory -> {
                    throw new IOException("not initialized");
                },
                directory -> JournalObservation.MISSING,
                (directory, manifest, deadline) -> HostObservation.unreachable());

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.INITIALIZATION_FAILED);
        assertThat(process.destroyCalls).isZero();
        assertThat(sessions.resolve("session-1")).doesNotExist();
    }

    @Test
    void preservesAReadableJournalAfterAnEarlyHostExit() {
        process.alive = false;
        NativeRuntime runtime = runtime(
                directory -> {
                    throw new IOException("manifest is not initialized");
                },
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.unreachable());

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.INITIALIZATION_FAILED);
        assertThat(process.destroyCalls).isZero();
        assertThat(sessions.resolve("session-1")).isDirectory();
    }

    @Test
    void preservesTheDirectoryAndReportsAJournalProbeFailureDuringCleanup() {
        process.alive = false;
        NativeRuntime runtime = runtime(
                directory -> {
                    throw new IOException("manifest is not initialized");
                },
                directory -> {
                    throw new IOException("journal probe failed");
                },
                (directory, manifest, deadline) -> HostObservation.unreachable());

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.CLEANUP_FAILED);
        assertThat(((SessionLaunchResult.Failed) result).detail()).contains("journal probe failed");
        assertThat(sessions.resolve("session-1")).isDirectory();
    }

    @Test
    void preservesDurableStateWhenTentativeProcessExitCannotBeConfirmed() {
        process.stopsWhenDestroyed = false;
        NativeRuntime runtime = runtime(
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.MISSING,
                (directory, manifest, deadline) -> HostObservation.unreachable());

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.CLEANUP_FAILED);
        assertThat(process.destroyCalls).isEqualTo(1);
        assertThat(process.forceCalls).isEqualTo(1);
        assertThat(sessions.resolve("session-1")).isDirectory();
    }

    @Test
    void initializationDeadlineCapsPollingSleepDeterministically() {
        AtomicLong nanoTime = new AtomicLong();
        List<Duration> sleeps = new ArrayList<>();
        NativeRuntime runtime = new NativeRuntime(
                executable,
                sessions,
                new ExistingDirectoryWorkspaceResolver(),
                launcher,
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> HostObservation.unreachable(),
                Duration.ofNanos(10),
                Duration.ofNanos(2),
                Duration.ofNanos(50),
                nanoTime::get,
                duration -> {
                    sleeps.add(duration);
                    nanoTime.addAndGet(duration.toNanos());
                });

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.INITIALIZATION_TIMEOUT);
        assertThat(sleeps).containsExactly(Duration.ofNanos(10));
    }

    @Test
    void deadlineAwareStatusCannotSucceedOrWaitAfterInitializationDeadline() {
        AtomicLong nanoTime = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        NativeRuntime runtime = new NativeRuntime(
                executable,
                sessions,
                new ExistingDirectoryWorkspaceResolver(),
                launcher,
                directory -> manifest(directory, 4242),
                directory -> JournalObservation.READABLE,
                (directory, manifest, deadline) -> {
                    assertThat(deadline.remainingNanos()).isPositive();
                    nanoTime.set(10);
                    return HostObservation.live(ChildState.LIVE);
                },
                Duration.ofNanos(10),
                Duration.ofNanos(2),
                Duration.ofNanos(5),
                nanoTime::get,
                duration -> sleeps.incrementAndGet());

        SessionLaunchResult result = runtime.launch(spec());

        assertFailure(result, SessionLaunchResult.FailureKind.INITIALIZATION_TIMEOUT);
        assertThat(sleeps).hasValue(0);
    }

    private NativeRuntime runtime(
            pro.deta.orion.agentd.session.SessionManifestReader manifestReader,
            pro.deta.orion.agentd.session.JournalProbe journalProbe,
            SessionHandoffProbe handoffProbe
    ) {
        return new NativeRuntime(
                executable,
                sessions,
                new ExistingDirectoryWorkspaceResolver(),
                launcher,
                manifestReader,
                journalProbe,
                handoffProbe,
                Duration.ofMillis(35),
                Duration.ofMillis(5),
                Duration.ofMillis(1));
    }

    private SessionSpec spec() {
        return new SessionSpec(
                new SessionId("session-1"),
                new CommandId("command.start"),
                List.of("sh", "-l"),
                new WorkspaceReference.ExistingDirectory(temporaryDirectory),
                Map.of(),
                80,
                24,
                "xterm-256color",
                Optional.of("truecolor"),
                SessionSpec.Sandbox.none());
    }

    private SessionSpec withPolicy(Path policy) {
        return new SessionSpec(
                spec().sessionId(), spec().startCommandId(), spec().command(), spec().workspace(),
                spec().environment(),
                spec().columns(), spec().rows(), spec().terminalType(), spec().colorTerminal(),
                new SessionSpec.Sandbox(Optional.of(policy)));
    }

    private static SessionManifest manifest(Path directory, long hostPid) {
        return new SessionManifest(
                1, 1, 1, "session-1", 1, 2, List.of("sh"), "/workspace", hostPid,
                OptionalLong.of(4343), 80, 24, 80, 24, "xterm-256color",
                new SessionManifest.Sandbox(false, "none", "fail", List.of(), List.of()),
                new ControlEndpoint(
                        ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                        "control.sock",
                        directory.resolve("control.sock")));
    }

    private static void assertFailure(SessionLaunchResult result, SessionLaunchResult.FailureKind kind) {
        assertThat(result).isInstanceOf(SessionLaunchResult.Failed.class);
        assertThat(((SessionLaunchResult.Failed) result).kind()).isEqualTo(kind);
    }

    private static final class CapturingLauncher implements DetachedProcessLauncher {
        private final TentativeProcess process;
        private List<String> command = new ArrayList<>();
        private Path logFile;

        private CapturingLauncher(TentativeProcess process) {
            this.process = process;
        }

        @Override
        public TentativeProcess launch(List<String> command, Path logFile) {
            this.command = List.copyOf(command);
            this.logFile = logFile;
            return process;
        }
    }

    private static final class FakeProcess implements DetachedProcessLauncher.TentativeProcess {
        private final long pid;
        private boolean alive;
        private boolean stopsWhenDestroyed;
        private int destroyCalls;
        private int forceCalls;

        private FakeProcess(long pid, boolean alive, boolean stopsWhenDestroyed) {
            this.pid = pid;
            this.alive = alive;
            this.stopsWhenDestroyed = stopsWhenDestroyed;
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
        public void destroy() {
            destroyCalls++;
            if (stopsWhenDestroyed) {
                alive = false;
            }
        }

        @Override
        public void destroyForcibly() {
            forceCalls++;
            if (stopsWhenDestroyed) {
                alive = false;
            }
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return !alive;
        }
    }
}
