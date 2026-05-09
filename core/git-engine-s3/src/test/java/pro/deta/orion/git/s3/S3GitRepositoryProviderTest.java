package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import pro.deta.orion.util.Result;

import static org.assertj.core.api.Assertions.assertThat;

class S3GitRepositoryProviderTest {
    @Test
    void reportsRepositoryOperationsAsNotSupportedUntilImplemented() {
        S3GitRepositoryProvider provider = new S3GitRepositoryProvider();

        assertThat(provider.exists("project")).isFalse();
        assertThat(provider.find("project"))
                .isInstanceOfSatisfying(Result.Failure.class, failure ->
                        assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_SUPPORTED));
        assertThat(provider.findOrCreate("project"))
                .isInstanceOfSatisfying(Result.Failure.class, failure ->
                        assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_SUPPORTED));
    }
}
