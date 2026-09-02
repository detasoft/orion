package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GitInteroperabilityMatrixRunner {
    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    final void runsScenario(GitMatrixInvocation invocation) throws Exception {
        GitInteroperabilityHarness.run(invocation.scenario(), invocation.client(), invocation.server());
    }

    protected abstract Stream<GitMatrixInvocation> matrixInvocations();

    private Stream<Arguments> matrix() {
        return matrixInvocations().map(Arguments::of);
    }
}
