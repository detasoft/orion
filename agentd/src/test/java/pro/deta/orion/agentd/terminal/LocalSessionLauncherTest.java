package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agentd.runtime.SessionLaunchResult;
import pro.deta.orion.agentd.runtime.SessionRuntime;
import pro.deta.orion.agentd.runtime.SessionSpec;
import pro.deta.orion.agentd.runtime.WorkspaceReference;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalSessionLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsTheNativeSessionSpecificationAndPrintsTheDurableHandoff() {
        Path executable = temporaryDirectory.resolve("session-host");
        Path state = temporaryDirectory.resolve("state");
        Path workspace = temporaryDirectory.resolve("workspace");
        AtomicReference<SessionSpec> launched = new AtomicReference<>();
        AtomicReference<Path> attached = new AtomicReference<>();
        SessionRuntime runtime = spec -> {
            launched.set(spec);
            return new SessionLaunchResult.Started(spec.sessionId(), state.resolve("sessions/session-one"));
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = new LocalSessionLauncher(
                Map.of("TERM", "screen", "COLORTERM", "truecolor"),
                (host, sessions) -> runtime,
                (directory, errors) -> {
                    attached.set(directory);
                    return 9;
                }).execute(new String[]{
                "--session-host", executable.toString(),
                "--state-dir", state.toString(),
                "--session-id", "session-one",
                "--cwd", workspace.toString(),
                "--", "/bin/sh", "-l"
        }, new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(9);
        assertThat(launched.get().sessionId().value()).isEqualTo("session-one");
        assertThat(launched.get().startCommandId().value()).startsWith("local-start-");
        assertThat(launched.get().command()).containsExactly("/bin/sh", "-l");
        assertThat(((WorkspaceReference.ExistingDirectory) launched.get().workspace()).directory())
                .isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(launched.get().columns()).isEqualTo(80);
        assertThat(launched.get().rows()).isEqualTo(24);
        assertThat(launched.get().terminalType()).isEqualTo("screen");
        assertThat(launched.get().colorTerminal()).contains("truecolor");
        assertThat(launched.get().environment()).isEmpty();
        assertThat(launched.get().sandbox()).isEqualTo(SessionSpec.Sandbox.none());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("session=session-one directory="
                        + state.resolve("sessions/session-one").toAbsolutePath().normalize() + "\n");
        assertThat(attached.get()).isEqualTo(state.resolve("sessions/session-one"));
    }

    @Test
    void generatesIdentifiersAndUsesSafeTerminalDefaults() {
        AtomicReference<SessionSpec> launched = new AtomicReference<>();
        SessionRuntime runtime = spec -> {
            launched.set(spec);
            return new SessionLaunchResult.Started(spec.sessionId(), temporaryDirectory.resolve("session"));
        };

        int exit = new LocalSessionLauncher(
                Map.of("TERM", "bad=value", "COLORTERM", "x".repeat(129)),
                (host, sessions) -> runtime,
                (directory, errors) -> 0).execute(new String[]{
                "--session-host", temporaryDirectory.resolve("session-host").toString(),
                "--state-dir", temporaryDirectory.resolve("state").toString(),
                "--", "/bin/cat"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(launched.get().sessionId().value()).startsWith("local-session-");
        assertThat(launched.get().terminalType()).isEqualTo("xterm-256color");
        assertThat(launched.get().colorTerminal()).isEmpty();
        assertThat(((WorkspaceReference.ExistingDirectory) launched.get().workspace()).directory())
                .isEqualTo(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void installsBundledSessionHostWhenTheOverrideIsAbsent() {
        Path state = temporaryDirectory.resolve("state");
        AtomicReference<Path> executable = new AtomicReference<>();
        SessionRuntime runtime = spec -> new SessionLaunchResult.Started(
                spec.sessionId(), state.resolve("sessions/session"));

        int exit = new LocalSessionLauncher(
                Map.of(),
                (host, sessions) -> {
                    executable.set(host);
                    return runtime;
                },
                (directory, errors) -> 0).execute(new String[]{
                "--state-dir", state.toString(),
                "--", "/bin/cat"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(executable.get()).isEqualTo(state.resolve("runtime/session-host").toAbsolutePath());
        assertThat(executable.get()).isExecutable();
    }

    @Test
    void explicitSessionHostBypassesBundledInstallation() {
        Path state = temporaryDirectory.resolve("state");
        Path override = temporaryDirectory.resolve("custom-session-host").toAbsolutePath();
        AtomicReference<Path> executable = new AtomicReference<>();
        SessionRuntime runtime = spec -> new SessionLaunchResult.Started(
                spec.sessionId(), state.resolve("sessions/session"));

        int exit = new LocalSessionLauncher(
                Map.of(),
                (host, sessions) -> {
                    executable.set(host);
                    return runtime;
                },
                (directory, errors) -> 0).execute(new String[]{
                "--session-host", override.toString(),
                "--state-dir", state.toString(),
                "--", "/bin/cat"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(executable.get()).isEqualTo(override);
        assertThat(Files.exists(state.resolve("runtime/session-host"))).isFalse();
    }

    @Test
    void reportsBundledInstallationFailureBeforeCreatingTheRuntime() throws Exception {
        Path state = Files.writeString(temporaryDirectory.resolve("state-file"), "not a directory");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exit = new LocalSessionLauncher(
                Map.of(),
                (host, sessions) -> {
                    throw new AssertionError("failed installation must not create the runtime");
                },
                (directory, attachErrors) -> {
                    throw new AssertionError("failed installation must not attach");
                }).execute(new String[]{
                "--state-dir", state.toString(),
                "--", "/bin/cat"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));

        assertThat(exit).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("Cannot install the bundled session-host", "runtime directory")
                .doesNotContain("/bin/cat");
    }

    @Test
    void separatesUsageErrorsFromBoundedLaunchFailures() {
        ByteArrayOutputStream usageErrors = new ByteArrayOutputStream();
        int invalid = new LocalSessionLauncher(
                Map.of(),
                (host, sessions) -> spec -> {
                    throw new AssertionError("invalid input must not launch");
                },
                (directory, errors) -> {
                    throw new AssertionError("invalid input must not attach");
                }).execute(
                new String[]{"--state-dir", temporaryDirectory.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(usageErrors));

        ByteArrayOutputStream launchErrors = new ByteArrayOutputStream();
        int failed = new LocalSessionLauncher(
                Map.of(),
                (host, sessions) -> spec -> SessionLaunchResult.failed(
                        SessionLaunchResult.FailureKind.INITIALIZATION_FAILED, "x".repeat(700)),
                (directory, errors) -> {
                    throw new AssertionError("failed launch must not attach");
                }).execute(new String[]{
                "--session-host", temporaryDirectory.resolve("session-host").toString(),
                "--state-dir", temporaryDirectory.resolve("state").toString(),
                "--", "/bin/secret-command"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(launchErrors));

        assertThat(invalid).isEqualTo(2);
        assertThat(usageErrors.toString(StandardCharsets.UTF_8)).contains("terminal start");
        assertThat(failed).isEqualTo(1);
        assertThat(launchErrors.toString(StandardCharsets.UTF_8))
                .startsWith("INITIALIZATION_FAILED: ")
                .doesNotContain("secret-command")
                .hasSizeLessThanOrEqualTo(512 + "INITIALIZATION_FAILED: \n".length());
    }
}
