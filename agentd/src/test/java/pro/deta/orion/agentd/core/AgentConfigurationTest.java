package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentConfigurationTest {
    @Test
    void parsesExplicitConfiguration() {
        AgentConfiguration configuration = AgentConfiguration.parse(new String[]{
                "--server", "https://agent.example.test/control",
                "--state-dir", "target/agent-state",
                "--agent-id", "agent-01KABC",
                "--generation", "7",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f",
                "--max-frame-bytes", "65536",
                "--agent-version", "1.2.3",
                "--session-host", "/opt/orion/releases/1.2.3/session-host"
        });

        assertThat(configuration.serverUri()).isEqualTo(URI.create("https://agent.example.test/control"));
        assertThat(configuration.stateDirectory()).isEqualTo(Path.of("target/agent-state").toAbsolutePath());
        assertThat(configuration.sessionsDirectory()).isEqualTo(
                Path.of("target/agent-state/sessions").toAbsolutePath());
        assertThat(configuration.processLockFile()).isEqualTo(
                Path.of("target/agent-state/agentd.lock").toAbsolutePath());
        assertThat(configuration.agentId().value()).isEqualTo("agent-01KABC");
        assertThat(configuration.generation().value()).isEqualTo(7);
        assertThat(configuration.launchId().value()).isEqualTo(
                UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f"));
        assertThat(configuration.protocolLimits().maxFrameBytes()).isEqualTo(65_536);
        assertThat(configuration.agentVersion()).isEqualTo("1.2.3");
        assertThat(configuration.sessionHostExecutable())
                .isEqualTo(Path.of("/opt/orion/releases/1.2.3/session-host"));
    }

    @Test
    void rejectsInsecureOrCredentialBearingServerUris() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(arguments("--server", "http://agent.test")))
                .withMessageContaining("HTTPS");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(
                        arguments("--server", "https://secret@agent.test")))
                .withMessageContaining("credentials");
    }

    @Test
    void rejectsMissingValuesAndOversizedFrames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(new String[]{"--server"}))
                .withMessageContaining("Missing value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(arguments("--max-frame-bytes", "16777217")))
                .withMessageContaining("maxFrameBytes");
    }

    @Test
    void rejectsRelativeSessionHostExecutable() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(
                        arguments("--session-host", "relative/session-host")))
                .withMessageContaining("absolute");
    }

    @Test
    void requiresEveryServerAssignedLaunchField() {
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfiguration.parse(new String[]{
                "--server", "https://agent.test",
                "--state-dir", "target/state",
                "--agent-id", "agent-1",
                "--generation", "1"
        })).withMessageContaining("--launch-id");
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfiguration.parse(new String[]{
                "--server", "https://agent.test",
                "--agent-id", "agent-1",
                "--generation", "1",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f"
        })).withMessageContaining("--state-dir");
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfiguration.parse(new String[]{
                "--server", "https://agent.test",
                "--state-dir", "target/state",
                "--agent-id", "agent-1",
                "--generation", "1",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f"
        })).withMessageContaining("--agent-version");
        assertThatIllegalArgumentException().isThrownBy(() -> AgentConfiguration.parse(new String[]{
                "--server", "https://agent.test",
                "--state-dir", "target/state",
                "--agent-id", "agent-1",
                "--generation", "1",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f",
                "--agent-version", "1.0.0"
        })).withMessageContaining("--session-host");
    }

    @Test
    void doesNotAcceptBootstrapCredentialsOnTheCommandLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(new String[]{
                        "--server", "https://agent.test",
                        "--bootstrap-token", "bootstrap-secret"
                }))
                .withMessageNotContaining("bootstrap-secret");
    }

    private static String[] arguments(String option, String value) {
        return new String[]{
                "--server", "https://agent.test",
                "--state-dir", "target/state",
                "--agent-id", "agent-1",
                "--generation", "1",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f",
                "--agent-version", "1.0.0",
                "--session-host", "/opt/orion/current/session-host",
                option, value
        };
    }
}
