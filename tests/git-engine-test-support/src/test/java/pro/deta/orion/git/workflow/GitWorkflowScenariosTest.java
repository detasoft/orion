package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GitWorkflowScenariosTest {
    private static final Set<String> REQUIRED_NAMES = Set.of(
            "initial-push-and-clone",
            "clone-multiple-commit-history",
            "fast-forward-push-and-pull",
            "alternating-two-client-round-trip",
            "multi-commit-single-push",
            "complex-file-update",
            "second-branch-fetch-and-checkout",
            "multi-ref-push",
            "reject-stale-non-fast-forward",
            "incremental-fetch-with-common-commit");

    @Test
    void declaresTheTenSymmetricWorkflowsOnceWithCapabilitiesAndTerminalState() {
        assertThat(GitWorkflowScenarios.catalog())
                .hasSize(10)
                .extracting(GitScenario::name)
                .containsExactlyInAnyOrderElementsOf(REQUIRED_NAMES);
        assertThat(GitWorkflowScenarios.catalog())
                .allSatisfy(scenario -> {
                    assertThat(scenario.requiredCapabilities()).isNotEmpty();
                    assertThat(scenario.requiredClientCapabilities()).isNotEmpty();
                    assertThat(scenario.requiredServerCapabilities()).isNotEmpty();
                    assertThat(scenario.expectedTerminalState()).isNotNull();
                    assertThat(scenario.expectedTerminalState().refs()).isNotEmpty();
                    assertThat(scenario.expectedTerminalState().commits()).isNotEmpty();
                });
    }

    @Test
    void keepsMissingRepositoryFirstPushOutsideTheSymmetricCatalog() {
        GitScenario extension = GitWorkflowScenarios.missingRepositoryFirstPush();

        assertThat(extension.name()).isEqualTo("orion-missing-repository-first-push");
        assertThat(GitWorkflowScenarios.catalog()).doesNotContain(extension);
        assertThat(extension.requiredCapabilities())
                .contains(GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH);
        assertThat(extension.requiredClientCapabilities())
                .doesNotContain(GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH);
        assertThat(extension.requiredServerCapabilities())
                .contains(GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH);
        assertThat(extension.remoteRepositoryMode()).isEqualTo(GitScenario.RemoteRepositoryMode.MISSING);
    }

    @Test
    void secondBranchScenarioDeclaresAnIndependentBranchCommit() {
        ExpectedRepositoryState state = GitWorkflowScenarios.catalog().stream()
                .filter(scenario -> scenario.name().equals("second-branch-fetch-and-checkout"))
                .findFirst()
                .orElseThrow()
                .expectedTerminalState();

        assertThat(state.refs().get("refs/heads/feature"))
                .isNotEqualTo(state.refs().get("refs/heads/main"));
        assertThat(state.commits()).hasSize(2);
    }
}

class GitWorkflowScenarioExecutionTest extends GitInteroperabilityMatrixRunner {
    @Override
    protected Stream<GitMatrixInvocation> matrixInvocations() {
        try {
            return GitWorkflowScenarios.catalog().stream()
                    .map(scenario -> invocation(scenario));
        } catch (UncheckedIOException error) {
            throw error;
        }
    }

    private static GitMatrixInvocation invocation(GitScenario scenario) {
        try {
            return new GitMatrixInvocation(scenario, GitClients.jgit(), GitServers.jgit());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
