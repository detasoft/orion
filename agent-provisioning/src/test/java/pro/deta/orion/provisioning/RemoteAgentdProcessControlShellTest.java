package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAgentdProcessControlShellTest {
    @Test
    void validatesCanonicalRecordAndLockBytesIncludingFinalNewlines(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);

        RemoteCommandResult result = fixture.executor.execute(
                fixture.control.metadataVerificationCommand(fixture.identity), new byte[0]);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutText()).isEqualTo("verified");
    }

    @Test
    void rejectsOversizedAndDanglingIdentityStateBeforeReading(@TempDir Path root) throws Exception {
        Fixture oversized = fixture(root.resolve("oversized"));
        Files.writeString(oversized.identityFile, "x".repeat(AgentdProcessRecord.MAX_BYTES + 1));

        RemoteCommandResult oversizedResult = oversized.executor.execute(
                oversized.control.metadataVerificationCommand(oversized.identity), new byte[0]);

        assertThat(oversizedResult.exitCode()).isEqualTo(79);

        Fixture dangling = fixture(root.resolve("dangling"));
        Files.delete(dangling.identityFile);
        Files.createSymbolicLink(dangling.identityFile, root.resolve("missing-target"));

        RemoteCommandResult danglingResult = dangling.executor.execute(
                dangling.control.metadataVerificationCommand(dangling.identity), new byte[0]);

        assertThat(danglingResult.exitCode()).isEqualTo(79);
    }

    @Test
    void rejectsStateDirectorySymlinkAndGroupReadableMetadata(@TempDir Path root) throws Exception {
        Fixture shared = fixture(root.resolve("shared"));
        Files.setPosixFilePermissions(shared.identityFile, PosixFilePermissions.fromString("rw-r-----"));
        RemoteCommandResult sharedResult = shared.executor.execute(
                shared.control.metadataVerificationCommand(shared.identity), new byte[0]);
        assertThat(sharedResult.exitCode()).isEqualTo(79);

        Fixture linked = fixture(root.resolve("linked-target"));
        Path stateLink = root.resolve("state-link");
        Files.createSymbolicLink(stateLink, linked.state);
        RemoteAgentdProcessControl linkedControl = new RemoteAgentdProcessControl(
                linked.executor, localPlatform(), stateLink.toString(), linked.install.toString(),
                System::nanoTime, duration -> { });
        RemoteCommandResult linkedResult = linked.executor.execute(
                linkedControl.metadataVerificationCommand(linked.identity), new byte[0]);
        assertThat(linkedResult.exitCode()).isEqualTo(79);

        Fixture unsafeDirectory = fixture(root.resolve("unsafe-directory"));
        Files.setPosixFilePermissions(
                unsafeDirectory.state, PosixFilePermissions.fromString("rwxr-x---"));
        RemoteCommandResult unsafeDirectoryResult = unsafeDirectory.executor.execute(
                unsafeDirectory.control.metadataVerificationCommand(unsafeDirectory.identity), new byte[0]);
        assertThat(unsafeDirectoryResult.exitCode()).isEqualTo(79);
    }

    @Test
    void rejectsDanglingLockSymlink(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Path lock = fixture.state.resolve("agentd.lock");
        Files.delete(lock);
        Files.createSymbolicLink(lock, root.resolve("missing-lock"));

        RemoteCommandResult result = fixture.executor.execute(
                fixture.control.metadataVerificationCommand(fixture.identity), new byte[0]);

        assertThat(result.exitCode()).isEqualTo(79);
    }

    @Test
    void linuxUnpublishedIdentityRequiresRealProcessOwnedKernelLock(@TempDir Path root) throws Exception {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            return;
        }
        Files.createDirectories(root);
        Path state = Files.createDirectories(root.resolve("state"));
        Path install = Files.createDirectories(root.resolve("install"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        long pid = ProcessHandle.current().pid();
        String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
        String[] fieldsAfterCommand = stat.substring(stat.lastIndexOf(") ") + 2).split(" ");
        String executable = Path.of("/proc", Long.toString(pid), "exe").toRealPath().toString();
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                pid, 1_000, fieldsAfterCommand[19], install.resolve("release").toString(), executable,
                new AgentLaunchId(UUID.randomUUID()), new AgentGeneration(1));
        Path lockPath = state.resolve("agentd.lock");
        Files.writeString(lockPath, lockText(identity));
        Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
        RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                new LocalShellExecutor(), RemotePlatform.LINUX_X86_64,
                state.toString(), install.toString(), System::nanoTime, duration -> { });

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            control.proveUnpublishedIdentity(identity);
        }

        try (FileChannel ignored = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
            assertThatThrownBy(() -> control.proveUnpublishedIdentity(identity))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        }
        assertThat(control.metadataVerificationCommand(identity)).contains("/fdinfo/").doesNotContain("/proc/locks");
    }

    @Test
    void linuxTerminationSignalsOnlyLockOwnerAndLeavesDetachedChildAlive(@TempDir Path root) throws Exception {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            return;
        }
        Path state = Files.createDirectories(root.resolve("state"));
        Path install = Files.createDirectories(root.resolve("install"));
        Path identities = Files.createDirectories(install.resolve("identities"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(identities, PosixFilePermissions.fromString("rwx------"));
        Path lockPath = state.resolve("agentd.lock");
        Files.writeString(lockPath, "");
        Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
        String program = "import fcntl,subprocess,sys,time; "
                + "f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX); "
                + "c=subprocess.Popen(['sleep','60'],start_new_session=True); "
                + "print(c.pid,flush=True); time.sleep(60)";
        Process owner = new ProcessBuilder("python3", "-c", program, lockPath.toString()).start();
        BufferedReader output = new BufferedReader(
                new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8));
        long childPid = Long.parseLong(output.readLine());
        try {
            long pid = owner.pid();
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            String[] fieldsAfterCommand = stat.substring(stat.lastIndexOf(") ") + 2).split(" ");
            String executable = Path.of("/proc", Long.toString(pid), "exe").toRealPath().toString();
            AgentdProcessIdentity identity = new AgentdProcessIdentity(
                    pid, 1_000, fieldsAfterCommand[19], install.resolve("release").toString(), executable,
                    new AgentLaunchId(UUID.randomUUID()), new AgentGeneration(1));
            Files.writeString(lockPath, lockText(identity));
            Path identityFile = identities.resolve(
                    identity.generation().value() + "-" + identity.launchId().value() + ".identity");
            Files.writeString(identityFile, AgentdProcessRecord.serialize(identity));
            Files.setPosixFilePermissions(identityFile, PosixFilePermissions.fromString("rw-------"));
            RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                    new LocalShellExecutor(), RemotePlatform.LINUX_X86_64,
                    state.toString(), install.toString(), System::nanoTime, Thread::sleep);
            AgentdRecoveryOptions options = new AgentdRecoveryOptions(
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(2), Duration.ofMillis(1), Duration.ofMillis(1), 1);

            control.terminate(identity, options);

            assertThat(ProcessHandle.of(childPid)).hasValueSatisfying(
                    child -> assertThat(child.isAlive()).isTrue());
        } finally {
            owner.destroyForcibly();
            ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void macOsReplacementFailsClosedBeforeRenderingPsIdentity(@TempDir Path root) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
            return;
        }
        Fixture fixture = fixture(root);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.control.inspect(fixture.identity))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
    }

    private static Fixture fixture(Path root) throws Exception {
        Files.createDirectories(root);
        Path state = Files.createDirectories(root.resolve("state"));
        Path install = Files.createDirectories(root.resolve("install"));
        Path identities = Files.createDirectories(install.resolve("identities"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(identities, PosixFilePermissions.fromString("rwx------"));
        AgentdProcessIdentity identity = identity(install);
        Path lock = state.resolve("agentd.lock");
        Files.writeString(lock, lockText(identity));
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-------"));
        Path identityFile = identities.resolve(identity.generation().value()
                + "-" + identity.launchId().value() + ".identity");
        Files.writeString(identityFile, AgentdProcessRecord.serialize(identity));
        Files.setPosixFilePermissions(identityFile, PosixFilePermissions.fromString("rw-------"));
        LocalShellExecutor executor = new LocalShellExecutor();
        RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                executor, localPlatform(), state.toString(), install.toString(),
                System::nanoTime, duration -> { });
        return new Fixture(executor, control, identity, state, install, identityFile);
    }

    private static AgentdProcessIdentity identity(Path install) {
        String release = install.resolve("releases/1").toString();
        return new AgentdProcessIdentity(
                ProcessHandle.current().pid(), 1_000, "native-token", release, release + "/agentd",
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                new AgentGeneration(7));
    }

    private static String lockText(AgentdProcessIdentity identity) {
        return "version=2\npid=" + identity.pid() + "\nstartEpochMillis=" + identity.startEpochMillis()
                + "\nlaunchId=" + identity.launchId().value() + "\ngeneration="
                + identity.generation().value() + "\nexecutable=" + identity.executable() + "\n";
    }

    private static RemotePlatform localPlatform() {
        String os = System.getProperty("os.name").toLowerCase().contains("mac") ? "Darwin" : "Linux";
        return RemotePlatform.parse(os, System.getProperty("os.arch"));
    }

    private record Fixture(
            LocalShellExecutor executor,
            RemoteAgentdProcessControl control,
            AgentdProcessIdentity identity,
            Path state,
            Path install,
            Path identityFile) {
    }
}
