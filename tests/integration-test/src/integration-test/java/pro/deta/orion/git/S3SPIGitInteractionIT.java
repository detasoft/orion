package pro.deta.orion.git;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pro.deta.orion.git.s3.AbstractClient;
import pro.deta.orion.git.s3.S3ServerRule;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiConsumer;

@Slf4j
@ExtendWith(S3ServerRule.class)
@DisplayName("Git protocol through the S3 NIO filesystem provider")
public class S3SPIGitInteractionIT extends BaseOrionTest implements S3ServerRule.AbstractClientAware, S3ServerRule.ServerSideAware {
    static {
        System.setProperty("s3.spi.endpoint-protocol", "http");
    }

    @Setter
    private S3ServerRule.ServerSide serverSide;
    @Setter
    private AbstractClient abstractClient;

    private void runScenarioInRepository(Path repositoryPath, BiConsumer<Repository, SoftAssertions> scenario) throws IOException {
        log.debug("Using test repo: {}", repositoryPath);

        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            repository.create(true);
            SoftAssertions.assertSoftly(assertions -> scenario.accept(repository, assertions));
        }
    }

    @Test
    @Disabled("S3 Git repository storage is currently unsupported; s3x SPI wiring is not a supported backend")
    @DisplayName("first pushed commit can be listed and fetched")
    void pushFirstCommitThenFetchIt() throws IOException {
        Path repositoryPath = Paths.get("s3x://%s:%s/%s/%s".formatted(
                serverSide.getHost(),
                serverSide.getPort(),
                abstractClient.getBucketName(),
                abstractClient.getPath()));

        runScenarioInRepository(repositoryPath, Scenarios::pushFirstCommitThenListAndFetch);
    }
}
