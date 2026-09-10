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
        SessionRuntime runtime = spec -> {
            launched.set(spec);
            return new SessionLaunchResult.Started(spec.sessionId(), state.resolve("sessions/session-one"));
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = LocalSessionLauncher.run(new String[]{
                "--session-host", executable.toString(),
                "--state-dir", state.toString(),
                "--session-id", "session-one",
                "--cwd", workspace.toString(),
                "--", "/bin/sh", "-l"
        }, new PrintStream(output), new PrintStream(new ByteArrayOutputStream()),
                Map.of("TERM", "screen", "COLORTERM", "truecolor"),
                (host, sessions) -> runtime);

        assertThat(exit).isZero();
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
    }

    @Test
    void generatesIdentifiersAndUsesSafeTerminalDefaults() {
        AtomicReference<SessionSpec> launched = new AtomicReference<>();
        SessionRuntime runtime = spec -> {
            launched.set(spec);
            return new SessionLaunchResult.Started(spec.sessionId(), temporaryDirectory.resolve("session"));
        };

        int exit = LocalSessionLauncher.run(new String[]{
                "--session-host", temporaryDirectory.resolve("session-host").toString(),
                "--state-dir", temporaryDirectory.resolve("state").toString(),
                "--", "/bin/cat"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
                Map.of("TERM", "bad=value", "COLORTERM", "x".repeat(129)),
                (host, sessions) -> runtime);

        assertThat(exit).isZero();
        assertThat(launched.get().sessionId().value()).startsWith("local-session-");
        assertThat(launched.get().terminalType()).isEqualTo("xterm-256color");
        assertThat(launched.get().colorTerminal()).isEmpty();
        assertThat(((WorkspaceReference.ExistingDirectory) launched.get().workspace()).directory())
                .isEqualTo(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void separatesUsageErrorsFromBoundedLaunchFailures() {
        ByteArrayOutputStream usageErrors = new ByteArrayOutputStream();
        int invalid = LocalSessionLauncher.run(
                new String[]{"--state-dir", temporaryDirectory.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(usageErrors), Map.of(),
                (host, sessions) -> spec -> {
                    throw new AssertionError("invalid input must not launch");
                });

        ByteArrayOutputStream launchErrors = new ByteArrayOutputStream();
        int failed = LocalSessionLauncher.run(new String[]{
                "--session-host", temporaryDirectory.resolve("session-host").toString(),
                "--state-dir", temporaryDirectory.resolve("state").toString(),
                "--", "/bin/secret-command"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(launchErrors), Map.of(),
                (host, sessions) -> spec -> SessionLaunchResult.failed(
                        SessionLaunchResult.FailureKind.INITIALIZATION_FAILED, "x".repeat(700)));

        assertThat(invalid).isEqualTo(2);
        assertThat(usageErrors.toString(StandardCharsets.UTF_8)).contains("terminal start");
        assertThat(failed).isEqualTo(1);
        assertThat(launchErrors.toString(StandardCharsets.UTF_8))
                .startsWith("INITIALIZATION_FAILED: ")
                .doesNotContain("secret-command")
                .hasSizeLessThanOrEqualTo(512 + "INITIALIZATION_FAILED: \n".length());
    }
}
