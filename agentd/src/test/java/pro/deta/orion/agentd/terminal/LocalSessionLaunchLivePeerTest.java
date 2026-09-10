package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionManifest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalSessionLaunchLivePeerTest {
    @TempDir
    Path temporaryDirectory;
    private Path stateDirectory;

    @AfterEach
    void removeShortStateDirectory() throws Exception {
        if (stateDirectory == null || !Files.exists(stateDirectory)) {
            return;
        }
        Files.walkFileTree(stateDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws java.io.IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, java.io.IOException failure)
                    throws java.io.IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    void returnsAfterDurableHandoffAndLeavesTheRealHostRunning() throws Exception {
        Path executable = extractSessionHost();
        stateDirectory = Files.createTempDirectory(Path.of("/tmp"), "orion-local-launch-");
        Path sessionDirectory = stateDirectory.resolve("sessions/local-live");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exit = LocalSessionLauncher.run(new String[]{
                "--session-host", executable.toString(),
                "--state-dir", stateDirectory.toString(),
                "--session-id", "local-live",
                "--cwd", temporaryDirectory.toString(),
                "--", "/bin/cat"
        }, new PrintStream(output), new PrintStream(errors));

        String hostLog = Files.exists(sessionDirectory.resolve("session-host.log"))
                ? Files.readString(sessionDirectory.resolve("session-host.log")) : "no host log";
        assertThat(exit).as(errors.toString(StandardCharsets.UTF_8) + hostLog).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("session=local-live directory=" + sessionDirectory + "\n");
        SessionManifest manifest = new JsonSessionManifestReader().read(sessionDirectory);
        ProcessHandle host = ProcessHandle.of(manifest.hostPid()).orElseThrow();
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
        try {
            assertThat(host.isAlive()).isTrue();
            assertThat(client.send(manifest.control(), new ControlCommand.Status()))
                    .isInstanceOf(ControlResult.Status.class);
        } finally {
            client.send(manifest.control(), new ControlCommand.Terminate(
                    1,
                    SessionCommandSource.MANUAL,
                    Optional.empty(),
                    AgentMessage.TerminationMode.FORCE));
            if (host.isAlive()) {
                host.onExit().get(5, TimeUnit.SECONDS);
            }
            if (host.isAlive()) {
                host.destroyForcibly();
            }
        }
    }

    private Path extractSessionHost() throws Exception {
        Path builtExecutable = Path.of("../session-host/target/cargo/debug/session-host");
        assertThat(builtExecutable).isRegularFile().isExecutable();
        Path executable = temporaryDirectory.resolve("session-host");
        Files.copy(builtExecutable, executable);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
