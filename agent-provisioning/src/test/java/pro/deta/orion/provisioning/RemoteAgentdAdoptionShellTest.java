package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteAgentdAdoptionShellTest {
    @Test
    void adoptsLiveLockOwnerAfterRecoveringPublicationAndReplacesExistingCurrent(@TempDir Path root)
            throws Exception {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            return;
        }
        Path state = Files.createDirectories(root.resolve("state"));
        Path install = Files.createDirectories(root.resolve("install"));
        Path identities = Files.createDirectories(install.resolve("identities"));
        Path oldRelease = Files.createDirectories(install.resolve("releases/old"));
        Path release = Files.createDirectories(install.resolve("releases/1"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(identities, PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(install.resolve("current"), Path.of("releases/old"));
        Process pythonLocation = new ProcessBuilder("python3", "-c", "import sys;print(sys.executable)").start();
        Path python = Path.of(new String(
                pythonLocation.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        assertThat(pythonLocation.waitFor()).isZero();
        Path agentd = Files.copy(python, release.resolve("agentd"));
        Files.setPosixFilePermissions(agentd, PosixFilePermissions.fromString("rwx------"));
        Path lockPath = state.resolve("agentd.lock");
        Files.writeString(lockPath, "");
        Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
        String program = "import fcntl,sys,time; f=open(sys.argv[1],'r+'); "
                + "fcntl.lockf(f,fcntl.LOCK_EX); print('ready',flush=True); time.sleep(60)";
        Process owner = new ProcessBuilder(agentd.toString(), "-c", program, lockPath.toString()).start();
        BufferedReader output = new BufferedReader(
                new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8));
        assertThat(output.readLine()).isEqualTo("ready");
        try {
            AgentdLaunchRequest request = request(state);
            long pid = owner.pid();
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            String[] fieldsAfterCommand = stat.substring(stat.lastIndexOf(") ") + 2).split(" ");
            String executable = Path.of("/proc", Long.toString(pid), "exe").toRealPath().toString();
            AgentdProcessIdentity identity = new AgentdProcessIdentity(
                    pid, 1_000, fieldsAfterCommand[19], release.toString(), executable,
                    request.launchId(), request.generation());
            Files.writeString(lockPath, lockText(identity));
            FaultOncePublisher executor = new FaultOncePublisher(new LocalShellExecutor());
            RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands(install.toString());

            AgentdReplacementResult adopted = provisioner.adoptProcessLockAndCommit(
                    executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1", release.toString()),
                    request, Duration.ofSeconds(2));

            assertThat(adopted.state()).isEqualTo(AgentdReplacementResult.State.ADOPTED);
            assertThat(adopted.identity()).isEqualTo(identity);
            assertThat(executor.publicationAttempts).isEqualTo(2);
            assertThat(Files.readSymbolicLink(install.resolve("current"))).isEqualTo(Path.of("releases/1"));
            assertThat(oldRelease.resolve("current.next-" + request.launchId().value())).doesNotExist();
            assertThat(identities.resolve(
                    request.generation().value() + "-" + request.launchId().value() + ".identity"))
                    .hasContent(AgentdProcessRecord.serialize(identity));

            AgentdReplacementResult readopted = provisioner.adoptAndCommit(
                    executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1", release.toString()),
                    request);
            assertThat(readopted.state()).isEqualTo(AgentdReplacementResult.State.ADOPTED);
            assertThat(readopted.identity()).isEqualTo(identity);
            assertThat(executor.publicationAttempts).isEqualTo(2);
        } finally {
            owner.destroyForcibly();
            owner.waitFor();
        }
    }

    private static AgentdLaunchRequest request(Path state) {
        return new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"), state.toString(),
                new AgentId("agent-1"), new AgentGeneration(7),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")), 1024, "1");
    }

    private static String lockText(AgentdProcessIdentity identity) {
        return "version=2\npid=" + identity.pid() + "\nstartEpochMillis=" + identity.startEpochMillis()
                + "\nlaunchId=" + identity.launchId().value() + "\ngeneration="
                + identity.generation().value() + "\nexecutable=" + identity.executable() + "\n";
    }

    private static final class FaultOncePublisher implements RemoteCommandExecutor {
        private final RemoteCommandExecutor delegate;
        private int publicationAttempts;

        private FaultOncePublisher(RemoteCommandExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public RemoteCommandResult execute(String command, byte[] input) throws ProvisioningException {
            if (command.contains(".identity.next") && publicationAttempts++ == 0) {
                return new RemoteCommandResult(73, new byte[0], new byte[0], false, false);
            }
            return delegate.execute(command, input);
        }
    }
}
