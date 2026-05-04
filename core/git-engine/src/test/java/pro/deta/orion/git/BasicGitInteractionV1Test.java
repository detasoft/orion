package pro.deta.orion.git;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static pro.deta.orion.git.JGitRuntimeAssertions.assertControlledJGitSystemReaderInstalled;
import static pro.deta.orion.git.JGitRuntimeAssertions.installDefaultControlledJGitRuntime;

@Slf4j
@DisplayName("Git classic protocol against a local bare repository")
@ResourceLock("jgit-system-reader")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class BasicGitInteractionV1Test extends BaseOrionTest {
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
        runScenarioInNewRepository(Scenarios::fetchEmptyRepositoryClassic);
    }

    @Test
    @DisplayName("first pushed commit is advertised")
    void firstPushedCommitIsAdvertised() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenListClassic);
    }

    @Test
    @DisplayName("first pushed commit can be fetched")
    void firstPushedCommitCanBeFetched() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFetchClassic);
    }

    @Test
    @DisplayName("second branch is advertised")
    void secondBranchIsAdvertised() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateSecondBranchAndListClassic);
    }

    @Test
    @DisplayName("tag is advertised")
    void tagIsAdvertised() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateTagAndListClassic);
    }

    @Test
    @DisplayName("annotated tag is advertised with peeled ref")
    void annotatedTagIsAdvertisedWithPeeledRef() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateAnnotatedTagAndListClassic);
    }

    @Test
    @DisplayName("deleted master disappears from advertisement")
    void deletedMasterDisappearsFromAdvertisement() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenDeleteMasterAndListClassic);
    }

    @Test
    @DisplayName("fast-forwarded master is advertised")
    void fastForwardedMasterIsAdvertised() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFastForwardMasterAndListClassic);
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
