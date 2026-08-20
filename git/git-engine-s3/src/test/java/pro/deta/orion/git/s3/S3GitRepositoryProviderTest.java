package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import pro.deta.orion.util.Result;

import static org.assertj.core.api.Assertions.assertThat;

class S3GitRepositoryProviderTest {
    @Test
    void reportsRepositoryOperationsAsNotSupportedUntilImplemented() {
        S3GitRepositoryProvider provider = new S3GitRepositoryProvider();

        assertThat(provider.exists("project")).isFalse();
        assertUnsupported(provider.find("project"));
        assertUnsupported(provider.findOrCreate("project"));
    }

    private static void assertUnsupported(Result<?> result) {
        assertThat(result)
                .isInstanceOfSatisfying(Result.Failure.class, failure ->
                        assertThat(failure)
                                .returns(Result.FailureCode.NOT_SUPPORTED, Result.Failure::code)
                                .returns(S3GitRepositoryProvider.NOT_SUPPORTED_MESSAGE, Result.Failure::message));
    }
}
