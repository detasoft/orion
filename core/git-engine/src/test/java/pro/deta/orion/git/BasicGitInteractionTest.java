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
@DisplayName("Git protocol against a local bare repository")
@ResourceLock("jgit-system-reader")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
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
    @DisplayName("protocol v2 capabilities are advertised")
    void protocolV2CapabilitiesAreAdvertised() throws IOException {
        runScenarioInNewRepository(Scenarios::advertiseProtocolV2Capabilities);
    }

    @Test
    @DisplayName("first pushed commit can be listed and fetched")
    void pushFirstCommitThenFetchIt() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenListAndFetch);
    }

    @Test
    @DisplayName("first pushed commit can be fetched without a standalone list step")
    void pushFirstCommitThenFetchItWithoutStandaloneList() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFetch);
    }

    @Test
    @DisplayName("HEAD symref can be listed after the first push")
    void pushedHeadSymrefCanBeListed() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenListHead);
    }

    @Test
    @DisplayName("missing branch prefix returns no refs after the first push")
    void missingBranchPrefixReturnsNoRefs() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenListUnknownBranch);
    }

    @Test
    @DisplayName("second branch can be created and listed")
    void secondBranchCanBeCreatedAndListed() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateSecondBranchAndListHeads);
    }

    @Test
    @DisplayName("tag can be created and listed")
    void tagCanBeCreatedAndListed() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateTagAndListTags);
    }

    @Test
    @DisplayName("master can be deleted after the first push")
    void masterCanBeDeletedAfterFirstPush() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenDeleteMasterAndListRefs);
    }

    @Test
    @DisplayName("master can be fast-forwarded after the first push")
    void masterCanBeFastForwardedAfterFirstPush() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFastForwardMasterAndListIt);
    }

    @Test
    @DisplayName("non-fast-forward update is rejected when configured")
    void nonFastForwardUpdateIsRejectedWhenConfigured() throws IOException {
        runScenarioInNewRepository(Scenarios::rejectNonFastForwardPushWhenConfigured);
    }

    @Test
    @DisplayName("force push updates master when non-fast-forward updates are allowed")
    void forcePushUpdatesMasterWhenAllowed() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenForcePushAndListMaster);
    }

    @Test
    @DisplayName("branch and tag can be created in one receive-pack")
    void branchAndTagCanBeCreatedInOneReceivePack() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFeatureBranchAndTagInOneReceivePack);
    }

    @Test
    @DisplayName("branch and tag can be created atomically in one receive-pack")
    void branchAndTagCanBeCreatedAtomicallyInOneReceivePack() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFeatureBranchAndTagAtomicallyInOneReceivePack);
    }

    @Test
    @DisplayName("annotated tag is listed with its peeled commit")
    void annotatedTagIsListedWithPeeledCommit() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateAnnotatedTagAndListPeeledTag);
    }

    @Test
    @DisplayName("annotated tag can be deleted")
    void annotatedTagCanBeDeleted() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenCreateAndDeleteAnnotatedTag);
    }

    @Test
    @DisplayName("repeated fetch with an existing have sends an empty pack")
    void repeatedFetchWithExistingHaveSendsEmptyPack() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFetchWithHave);
    }

    @Test
    @DisplayName("shallow fetch reports shallow boundary and sends the pack")
    void shallowFetchReportsBoundaryAndSendsPack() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFetchShallow);
    }

    @Test
    @DisplayName("fetching an unknown object finishes without returning a pack")
    void fetchingUnknownObjectFinishesWithoutReturningPack() throws IOException {
        runScenarioInNewRepository(Scenarios::pushFirstCommitThenFetchUnknownObject);
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
