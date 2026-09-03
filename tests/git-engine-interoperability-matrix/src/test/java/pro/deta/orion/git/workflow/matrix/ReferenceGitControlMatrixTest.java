package pro.deta.orion.git.workflow.matrix;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import pro.deta.orion.git.workflow.GitInteroperabilityMatrixRunner;
import pro.deta.orion.git.workflow.GitMatrixInvocation;

import java.util.stream.Stream;

@EnabledIfSystemProperty(named = "git.matrix.controls", matches = "true")
class ReferenceGitControlMatrixTest extends GitInteroperabilityMatrixRunner {
    @Override
    protected Stream<GitMatrixInvocation> matrixInvocations() {
        return GitMatrixDefinition.controlCases().stream();
    }
}
