package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisioningContractsTest {
    @Test
    void validatesEndpointAndCredentials() throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();

        SshEndpoint endpoint = new SshEndpoint("host.example", 22, "orion", host.getPublic());
        SshCredentials credentials = new SshCredentials(client);

        assertThat(endpoint.host()).isEqualTo("host.example");
        assertThat(credentials.keyPair()).isNotSameAs(client);
        assertThatThrownBy(() -> new SshEndpoint("host\nname", 22, "orion", host.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SshEndpoint("host", 0, "orion", host.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SshEndpoint("host", 22, "bad user", host.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesSupportedRemotePlatforms() {
        assertThat(RemotePlatform.parse("Linux", "x86_64"))
                .isEqualTo(RemotePlatform.LINUX_X86_64);
        assertThat(RemotePlatform.parse("Darwin", "arm64"))
                .isEqualTo(RemotePlatform.MACOS_AARCH64);
        assertThatThrownBy(() -> RemotePlatform.parse("Plan9", "mips"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported remote platform");
    }

    @Test
    void selectsExactlyOneValidatedBundle(@TempDir Path temporaryDirectory) throws Exception {
        Path agentd = Files.writeString(temporaryDirectory.resolve("agentd"), "agent");
        Path sessionHost = Files.writeString(temporaryDirectory.resolve("session-host"), "host");
        RemoteRuntimeBundle bundle = new RemoteRuntimeBundle(
                "1.2.3",
                RemotePlatform.LINUX_X86_64,
                RuntimeArtifact.from(agentd, "agentd"),
                RuntimeArtifact.from(sessionHost, "session-host"));
        RemoteRuntimeBundle nextBundle = new RemoteRuntimeBundle(
                "1.2.4",
                RemotePlatform.LINUX_X86_64,
                RuntimeArtifact.from(agentd, "agentd"),
                RuntimeArtifact.from(sessionHost, "session-host"));

        RuntimeBundleCatalog catalog = new RuntimeBundleCatalog(java.util.List.of(bundle, nextBundle));

        assertThat(catalog.select(RemotePlatform.LINUX_X86_64, "1.2.3")).isSameAs(bundle);
        assertThat(catalog.select(RemotePlatform.LINUX_X86_64, "1.2.4")).isSameAs(nextBundle);
        assertThatThrownBy(() -> catalog.select(RemotePlatform.MACOS_X86_64, "1.2.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No runtime bundle");
        assertThatThrownBy(() -> new RemoteRuntimeBundle(
                "../escape", RemotePlatform.LINUX_X86_64,
                bundle.agentd(), bundle.sessionHost()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuntimeArtifact.withExpectedDigest(
                agentd, "agentd", "ABC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteRuntimeBundle(
                "custom-names",
                RemotePlatform.LINUX_X86_64,
                RuntimeArtifact.from(agentd, "custom-agent"),
                RuntimeArtifact.from(sessionHost, "custom-host")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("named agentd and session-host");
    }

    @Test
    void validatesLaunchAndTimeoutOptions() {
        AgentdLaunchRequest request = new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"),
                "/var/lib/orion/agent",
                new AgentId("agent-1"),
                new AgentGeneration(3),
                new AgentLaunchId(UUID.randomUUID()),
                1_048_576,
                "1.2.3");
        ProvisioningOptions options = new ProvisioningOptions(
                Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(4), Duration.ofSeconds(10));

        assertThat(request.generation().value()).isEqualTo(3);
        assertThat(options.operationTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThatThrownBy(() -> new AgentdLaunchRequest(
                URI.create("http://orion.example"), request.stateDirectory(),
                request.agentId(), request.generation(), request.launchId(),
                request.maxFrameBytes(), request.agentVersion()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningOptions(
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void launchPermitCopiesAndClearsSensitiveBytes() throws Exception {
        byte[] source = "permit-value".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProvisioningLaunchPermit permit = new ProvisioningLaunchPermit(source);
        Arrays.fill(source, (byte) 0);

        byte[] first = permit.copyBytes();
        first[0] = 'X';
        assertThat(new String(permit.copyBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("permit-value");

        permit.close();

        assertThatThrownBy(permit::copyBytes).isInstanceOf(IllegalStateException.class);
        Field bytes = ProvisioningLaunchPermit.class.getDeclaredField("bytes");
        bytes.setAccessible(true);
        assertThat((byte[]) bytes.get(permit)).containsOnly((byte) 0);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
