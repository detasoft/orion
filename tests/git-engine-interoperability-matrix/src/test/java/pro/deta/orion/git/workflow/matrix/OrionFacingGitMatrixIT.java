package pro.deta.orion.git.workflow.matrix;

import pro.deta.orion.git.workflow.GitInteroperabilityMatrixRunner;
import pro.deta.orion.git.workflow.GitMatrixInvocation;

import java.util.stream.Stream;

class OrionFacingGitMatrixIT extends GitInteroperabilityMatrixRunner {
    @Override
    protected Stream<GitMatrixInvocation> matrixInvocations() {
        return GitMatrixDefinition.requiredCases().stream();
    }
}
