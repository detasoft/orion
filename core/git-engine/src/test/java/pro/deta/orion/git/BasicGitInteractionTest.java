package pro.deta.orion.git;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

import static pro.deta.orion.git.Scenarios.fetchEmptyRepo;

@Slf4j
public class BasicGitInteractionTest extends BaseOrionTest {
    @Test
    public void fetchEmptyRepo() throws IOException {
        runTestInRepo(fetchEmptyRepo);
    }

    private void runTestInRepo(BiConsumer<Repository, SoftAssertions> repositoryConsumer) throws IOException {
        Path dir = Files.createTempDirectory(getTestInfo().getTestClass().get().getSimpleName() +"-" + getTestInfo().getTestMethod().get().getName());
        log.debug("Using test repo: {}", dir);
        try (Repository r = FileRepositoryBuilder.create(dir.toFile())) {
            r.create(true);
            SoftAssertions.assertSoftly(sa -> {
                repositoryConsumer.accept(r, sa);
            });
        }
    }

    @Test
    void simplePatchToReceive() throws IOException {
        runTestInRepo(Scenarios.simplePatchToReceive);
    }
}
