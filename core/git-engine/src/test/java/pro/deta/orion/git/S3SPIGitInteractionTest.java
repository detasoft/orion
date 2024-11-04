package pro.deta.orion.git;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Disabled;
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
public class S3SPIGitInteractionTest extends BaseOrionTest implements S3ServerRule.AbstractClientAware, S3ServerRule.ServerSideAware {
    static {
        System.setProperty("s3.spi.endpoint-protocol", "http");
    }

    @Setter
    private S3ServerRule.ServerSide serverSide;
    @Setter
    private AbstractClient abstractClient;

    private void runTestInRepo(Path repoPath, BiConsumer<Repository, SoftAssertions> repositoryConsumer) throws IOException {
        log.debug("Using test repo: {}", repoPath);
        try (Repository r = FileRepositoryBuilder.create(repoPath.toFile())) {
            r.create(true);
            SoftAssertions.assertSoftly(sa -> {
                repositoryConsumer.accept(r, sa);
            });
        }
    }

    @Test
    @Disabled("need to properly inject s3x: into filesystem")
    void simplePatchToReceive() throws IOException {
        String p = "s3x://%s:%s/%s/%s".formatted(serverSide.getHost(), serverSide.getPort(), abstractClient.getBucketName(), abstractClient.getPath());
        runTestInRepo(Paths.get(p), Scenarios.simplePatchToReceive);
    }
}
