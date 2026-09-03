package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAgentdCurrentSwitchShellTest {
    @Test
    void unsafePartialNextDirectoryLeavesCurrentUnchanged(@TempDir Path root) throws Exception {
        Path current = root.resolve("current");
        Files.createSymbolicLink(current, Path.of("releases/old"));
        AgentdLaunchRequest request = request();
        Path next = root.resolve("current.next-" + request.launchId().value());
        Files.createDirectories(next);
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands(root.toString());

        assertThatThrownBy(() -> provisioner.switchCurrent(
                new LocalShellExecutor(), "new", request, localPlatform()))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNSAFE_IDENTITY);

        assertThat(Files.readSymbolicLink(current)).isEqualTo(Path.of("releases/old"));
        assertThat(next).isDirectory();
    }

    @Test
    void unsafeCurrentDirectoryIsPreservedAndTemporaryLinkIsRemoved(@TempDir Path root) throws Exception {
        Path current = Files.createDirectories(root.resolve("current"));
        Files.writeString(current.resolve("keep"), "old");
        AgentdLaunchRequest request = request();
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands(root.toString());

        assertThatThrownBy(() -> provisioner.switchCurrent(
                new LocalShellExecutor(), "new", request, localPlatform()))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNSAFE_IDENTITY);

        assertThat(current.resolve("keep")).hasContent("old");
        assertThat(root.resolve("current.next-" + request.launchId().value())).doesNotExist();
    }

    @Test
    void replacesCurrentSymlinkToExistingReleaseWithoutNestingTemporaryLink(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve("releases/old"));
        Files.createDirectories(root.resolve("releases/new"));
        Path current = root.resolve("current");
        Files.createSymbolicLink(current, Path.of("releases/old"));
        AgentdLaunchRequest request = request();
        Path next = root.resolve("current.next-" + request.launchId().value());
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands(root.toString());

        provisioner.switchCurrent(new LocalShellExecutor(), "new", request, localPlatform());

        assertThat(Files.readSymbolicLink(current)).isEqualTo(Path.of("releases/new"));
        assertThat(next).doesNotExist();
        assertThat(root.resolve("releases/old").resolve(next.getFileName())).doesNotExist();
    }

    private static RemotePlatform localPlatform() {
        String os = System.getProperty("os.name").toLowerCase().contains("mac") ? "Darwin" : "Linux";
        return RemotePlatform.parse(os, System.getProperty("os.arch"));
    }

    private static AgentdLaunchRequest request() {
        return new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"), "/var/lib/orion/agent",
                new AgentId("agent-1"), new AgentGeneration(7),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")), 1024, "new");
    }
}
