package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AgentdRecoveryContractsTest {
    private static final AgentLaunchId LAUNCH_ID =
            new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f"));
    private static final AgentGeneration GENERATION = new AgentGeneration(7);

    @Test
    void processIdentityRequiresExactNormalizedPathsAndBoundedNativeToken() {
        AgentdProcessIdentity identity = identity();

        assertThat(identity.executable()).isEqualTo("/opt/orion/releases/1/agentd");
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentdProcessIdentity(
                42, 1_000, "token", "/opt/orion/releases/../1", "/opt/orion/releases/1/agentd",
                LAUNCH_ID, GENERATION));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentdProcessIdentity(
                42, 1_000, "bad\ntoken", "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                LAUNCH_ID, GENERATION));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentdProcessIdentity(
                0, 1_000, "token", "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                LAUNCH_ID, GENERATION));
    }

    @Test
    void recoveryOptionsRequirePositiveOrderedBounds() {
        AgentdRecoveryOptions options = new AgentdRecoveryOptions(
                Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(8), 4);

        assertThat(options.maximumAttempts()).isEqualTo(4);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentdRecoveryOptions(
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentdRecoveryOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(1), 1));
    }

    @Test
    void launchAttemptOwnsAndRedactsPermit() {
        byte[] secret = "secret-permit".getBytes(StandardCharsets.US_ASCII);
        ProvisioningLaunchPermit permit = new ProvisioningLaunchPermit(secret);
        AgentdLaunchAttempt attempt = new AgentdLaunchAttempt(request(), permit);

        assertThat(attempt.toString()).doesNotContain("secret-permit");
        attempt.close();
        assertThatIllegalStateException().isThrownBy(permit::copyBytes);
    }

    private static AgentdProcessIdentity identity() {
        return new AgentdProcessIdentity(
                42, 1_000, "native-token", "/opt/orion/releases/1",
                "/opt/orion/releases/1/agentd", LAUNCH_ID, GENERATION);
    }

    private static AgentdLaunchRequest request() {
        return new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"), "/var/lib/orion/agent",
                new AgentId("agent-1"), GENERATION, LAUNCH_ID, 1024, "1");
    }
}
