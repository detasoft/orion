package pro.deta.orion.git.workflow.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.workflow.GitCapability;

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitEnginesTest {
    @Test
    void exposesExplicitCompleteClientAndServerCapabilities() throws Exception {
        var client = OrionGitEngines.client();
        var server = OrionGitEngines.server();

        try (server) {
            assertThat(client.name()).isEqualTo("orion");
            assertThat(server.name()).isEqualTo("orion");
            assertThat(client.capabilities()).containsExactlyInAnyOrderElementsOf(GitCapability.all());
            assertThat(server.capabilities()).containsExactlyInAnyOrderElementsOf(GitCapability.all());
        }
    }
}
