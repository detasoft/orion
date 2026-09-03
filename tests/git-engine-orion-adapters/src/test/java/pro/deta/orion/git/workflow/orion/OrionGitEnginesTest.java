package pro.deta.orion.git.workflow.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.workflow.GitCapability;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitEnginesTest {
    @Test
    void exposesExplicitCompleteClientAndServerCapabilities() throws Exception {
        var client = OrionGitEngines.client();
        var server = OrionGitEngines.server();

        try (server) {
            assertThat(client.name()).isEqualTo("orion");
            assertThat(server.name()).isEqualTo("orion");
            assertThat(client.capabilities()).containsExactlyInAnyOrderElementsOf(GitCapability.symmetric());
            assertThat(server.capabilities()).containsExactlyInAnyOrderElementsOf(Set.of(
                    GitCapability.INITIALIZE,
                    GitCapability.CLONE,
                    GitCapability.COMMIT,
                    GitCapability.PUSH,
                    GitCapability.FETCH,
                    GitCapability.FAST_FORWARD_PULL,
                    GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH));
        }
    }
}
