package pro.deta.orion.git.s3;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pro.deta.orion.git.BaseOrionTest;
import pro.deta.orion.git.Scenarios;

import java.io.IOException;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@ExtendWith(S3ServerRule.class)
@Setter
@DisplayName("Git repository stored directly in S3")
public class S3GitRepositoryIT extends BaseOrionTest implements S3ServerRule.AbstractClientAware {
    private AbstractClient abstractClient;

    @Test
    @DisplayName("configured bucket exists")
    void bucketExists() {
        assertThat(abstractClient.listBuckets()).contains(abstractClient.getBucketName());
    }

    @Test
    @Disabled("investigation needed")
    @DisplayName("first pushed commit can be listed and fetched")
    void pushFirstCommitThenFetchIt() throws IOException {
        runScenarioInFreshRepository(Scenarios::pushFirstCommitThenListAndFetchWithoutCapabilities);
    }

    private void runScenarioInFreshRepository(BiConsumer<Repository, SoftAssertions> scenario) throws IOException {
        removeBucketIfPresent();

        try (S3Repository repository = new S3Repository(abstractClient)) {
            repository.create(true);
            SoftAssertions.assertSoftly(assertions -> scenario.accept(repository, assertions));
        }
    }

    private void removeBucketIfPresent() {
        try {
            abstractClient.removeBucket();
        } catch (Exception e) {
            log.debug("S3 test bucket was not available for cleanup", e);
        }
    }
}
