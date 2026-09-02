package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentConfigurationTest {
    @Test
    void parsesExplicitConfiguration() {
        AgentConfiguration configuration = AgentConfiguration.parse(new String[]{
                "--server", "https://agent.example.test/control",
                "--state-dir", "target/agent-state",
                "--max-frame-bytes", "65536",
                "--agent-version", "1.2.3"
        });

        assertThat(configuration.serverUri()).isEqualTo(URI.create("https://agent.example.test/control"));
        assertThat(configuration.stateDirectory()).isEqualTo(Path.of("target/agent-state").toAbsolutePath());
        assertThat(configuration.sessionsDirectory()).isEqualTo(
                Path.of("target/agent-state/sessions").toAbsolutePath());
        assertThat(configuration.protocolLimits().maxFrameBytes()).isEqualTo(65_536);
        assertThat(configuration.agentVersion()).isEqualTo("1.2.3");
    }

    @Test
    void rejectsInsecureOrCredentialBearingServerUris() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(new String[]{"--server", "http://agent.test"}))
                .withMessageContaining("HTTPS");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(
                        new String[]{"--server", "https://secret@agent.test"}))
                .withMessageContaining("credentials");
    }

    @Test
    void rejectsMissingValuesAndOversizedFrames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(new String[]{"--server"}))
                .withMessageContaining("Missing value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AgentConfiguration.parse(new String[]{
                        "--server", "https://agent.test",
                        "--max-frame-bytes", "16777217"
                }))
                .withMessageContaining("maxFrameBytes");
    }
}
