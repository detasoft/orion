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
            "git -> orion",
            "orion -> orion-http", "jgit -> orion-http", "git -> orion-http",
            "orion -> orion-ssh", "jgit -> orion-ssh", "git -> orion-ssh");
    private static final Set<String> CONTROL_PAIRS = Set.of(
            "jgit -> jgit",
            "jgit -> git",
            "git -> jgit",
            "git -> git");

    @Test
    void definesAllTransportInvocations() {
        List<GitMatrixInvocation> cases = GitMatrixDefinition.requiredCases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(143);
        assertThat(cases).extracting(GitMatrixInvocation::displayName).doesNotHaveDuplicates();
        assertThat(cases).extracting(GitMatrixInvocation::pairName)
                .containsOnlyElementsOf(REQUIRED_PAIRS);
        assertThat(cases).extracting(matrixCase -> matrixCase.scenario().name())
                .containsOnlyElementsOf(scenarioNames());
    }

    @Test
    void keepsReferenceOnlyControlInvocationsAvailable() {
        List<GitMatrixInvocation> cases = GitMatrixDefinition.controlCases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(52);
        assertThat(cases).extracting(GitMatrixInvocation::displayName).doesNotHaveDuplicates();
        assertThat(cases).extracting(GitMatrixInvocation::pairName)
                .containsOnlyElementsOf(CONTROL_PAIRS);
    }

    @Test
    void rejectsMissingRequiredCoverageInsteadOfSkippingIt() {
        List<GitMatrixInvocation> cases = GitMatrixDefinition.requiredCases();
        List<GitMatrixInvocation> incomplete = cases.subList(1, cases.size());

        assertThatThrownBy(() -> GitMatrixDefinition.requireCompleteCoverage(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing=[" + cases.getFirst().displayName() + "]");
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

    private static Set<String> scenarioNames() {
        Set<String> names = new HashSet<>();
        for (var scenario : GitWorkflowScenarios.catalog()) {
            names.add(scenario.name());
        }
        return names;
    }
}
