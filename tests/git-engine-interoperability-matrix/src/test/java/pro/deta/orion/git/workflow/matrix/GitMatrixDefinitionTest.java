package pro.deta.orion.git.workflow.matrix;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitMatrixInvocation;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkflowScenarios;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitMatrixDefinitionTest {
    private static final Set<String> REQUIRED_PAIRS = Set.of(
            "orion -> orion",
            "orion -> jgit",
            "orion -> git",
            "jgit -> orion",
            "git -> orion");
    private static final Set<String> CONTROL_PAIRS = Set.of(
            "jgit -> jgit",
            "jgit -> git",
            "git -> jgit",
            "git -> git");

    @Test
    void definesFiftyUniqueRequiredOrionFacingInvocations() {
        List<GitMatrixInvocation> cases = GitMatrixDefinition.requiredCases();

        assertThat(cases).hasSize(50);
        assertThat(cases).extracting(GitMatrixInvocation::displayName).doesNotHaveDuplicates();
        assertThat(cases).extracting(GitMatrixInvocation::pairName)
                .containsOnlyElementsOf(REQUIRED_PAIRS);
        assertThat(countsByPair(cases).values()).containsOnly(10);
        assertThat(countsByScenario(cases).values()).containsOnly(5);
        assertThat(countsByScenario(cases).keySet())
                .containsExactlyInAnyOrderElementsOf(scenarioNames());
    }

    @Test
    void keepsFortyReferenceOnlyControlInvocationsAvailable() {
        List<GitMatrixInvocation> cases = GitMatrixDefinition.controlCases();

        assertThat(cases).hasSize(40);
        assertThat(cases).extracting(GitMatrixInvocation::displayName).doesNotHaveDuplicates();
        assertThat(cases).extracting(GitMatrixInvocation::pairName)
                .containsOnlyElementsOf(CONTROL_PAIRS);
        assertThat(countsByPair(cases).values()).containsOnly(10);
        assertThat(countsByScenario(cases).values()).containsOnly(4);
    }

    @Test
    void rejectsMissingRequiredCoverageInsteadOfSkippingIt() {
        List<GitMatrixInvocation> incomplete = GitMatrixDefinition.requiredCases().subList(1, 50);

        assertThatThrownBy(() -> GitMatrixDefinition.requireCompleteCoverage(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=50")
                .hasMessageContaining("actual=49");
    }

    @Test
    void rejectsFactoryWhoseEngineDoesNotMatchTheDeclaredPair() {
        GitMatrixInvocation matrixCase = new GitMatrixInvocation(
                GitWorkflowScenarios.catalog().getFirst(),
                "orion",
                "jgit",
                GitClients::jgit,
                GitServers::jgit);

        assertThatThrownBy(matrixCase::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared client=orion")
                .hasMessageContaining("actual=jgit");
    }

    private static java.util.Map<String, Integer> countsByPair(List<GitMatrixInvocation> cases) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (GitMatrixInvocation matrixCase : cases) {
            counts.merge(matrixCase.pairName(), 1, Integer::sum);
        }
        return counts;
    }

    private static java.util.Map<String, Integer> countsByScenario(List<GitMatrixInvocation> cases) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (GitMatrixInvocation matrixCase : cases) {
            counts.merge(matrixCase.scenario().name(), 1, Integer::sum);
        }
        return counts;
    }

    private static Set<String> scenarioNames() {
        Set<String> names = new HashSet<>();
        for (var scenario : GitWorkflowScenarios.catalog()) {
            names.add(scenario.name());
        }
        return names;
    }
}
