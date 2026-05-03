package pro.deta.orion.git;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;

import static pro.deta.orion.git.JGitRuntimeAssertions.assertControlledJGitSystemReaderInstalled;
import static pro.deta.orion.git.JGitRuntimeAssertions.installDefaultControlledJGitRuntime;

@Slf4j
@DisplayName("Git protocol against a local bare repository")
@ResourceLock("jgit-system-reader")
public class BasicGitInteractionTest extends BaseOrionTest {
    @BeforeEach
    void installControlledJGitRuntime() {
        installDefaultControlledJGitRuntime();
    }

    @AfterEach
    void resetControlledJGitRuntime() {
        try {
            assertControlledJGitSystemReaderInstalled();
        } finally {
            installDefaultControlledJGitRuntime();
        }
    }

    @Test
    @DisplayName("empty repository advertises no refs")
    void fetchEmptyRepository() throws IOException {
        runScenarioInNewRepository(Scenarios::fetchEmptyRepository);
    }

    @Test
    @DisplayName("first pushed commit can be listed and fetched")
    void pushFirstCommitThenFetchIt() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenListAndFetch);
    }

    private void runScenarioInNewRepository(BiConsumer<Repository, SoftAssertions> scenario) throws IOException {
        assertControlledJGitSystemReaderInstalled();
        Path repositoryDirectory = createTestRepositoryDirectory();
        log.debug("Using test repo: {}", repositoryDirectory);

        try (Repository repository = FileRepositoryBuilder.create(repositoryDirectory.toFile())) {
            repository.create(true);
            SoftAssertions.assertSoftly(assertions -> scenario.accept(repository, assertions));
        }
    }

}
