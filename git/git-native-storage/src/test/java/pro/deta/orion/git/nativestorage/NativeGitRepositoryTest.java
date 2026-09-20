package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void exposesIdentityAndFreshRefSnapshots() {
        try (NativeGitRepository repository = repository()) {
            assertThat(repository.name()).isEqualTo("demo.git");
            assertThat(repository.defaultHead()).isEqualTo("refs/heads/main");
            Map<String, String> before = repository.refs();
            ObjectId blob = repository.writeObject(GitObjectType.BLOB, "published".getBytes(StandardCharsets.UTF_8));
            RefUpdateResult result = repository.updateRef("refs/heads/main", NULL_ID, blob.toHex());
            assertThat(result.status()).isEqualTo(RefUpdateResult.Status.APPLIED);
            assertThat(before).isEmpty();
            assertThat(repository.refs()).containsExactlyEntriesOf(Map.of("refs/heads/main", blob.toHex()));
            assertThat(repository.readObject(blob)).isPresent();
        }
    }

    @Test
    void reportsDirectAndPackRefUpdatesAfterPublication() throws Exception {
        try (NativeGitRepository repository = repository()) {
            ObjectId blob = repository.writeObject(GitObjectType.BLOB, "first".getBytes(StandardCharsets.UTF_8));
            List<RefUpdateResult> updates = new ArrayList<>();
            repository.onRefUpdate(result -> {
                assertThat(repository.refs()).containsEntry(result.update().ref().value(),
                        result.update().newId().orElseThrow().toHex());
                updates.add(result);
            });
            repository.updateRef("refs/heads/main", NULL_ID, blob.toHex());
            NativeGitFileUpdate prepared = repository.prepareFileUpdate("configuration",
                    Map.of("config.txt", new byte[]{1}), "configuration", GitCommitAuthor.EMPTY);
            repository.publishPack(prepared.pack(), prepared.refUpdates(), true,
                    GitNativeRepositoryAccessHook.ALLOW_ALL);
            assertThat(updates).extracting(result -> result.update().ref().value())
                    .containsExactly("refs/heads/main", "refs/heads/configuration");
        }
    }

    @Test
    void doesNotReportRejectedAtomicRefUpdates() {
        try (NativeGitRepository repository = repository()) {
            ObjectId blob = repository.writeObject(GitObjectType.BLOB, new byte[]{1});
            repository.updateRef("refs/heads/main", NULL_ID, blob.toHex());
            List<RefUpdateResult> updates = new ArrayList<>();
            repository.onRefUpdate(updates::add);
            List<RefUpdateResult> result = repository.publishRefs(List.of(
                    RefUpdate.fromWire("refs/heads/topic", NULL_ID, blob.toHex()),
                    RefUpdate.fromWire("refs/heads/main", "3".repeat(40), blob.toHex())), true);
            assertThat(result).extracting(RefUpdateResult::status).containsExactly(
                    RefUpdateResult.Status.ATOMIC_ABORTED, RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
            assertThat(updates).isEmpty();
            assertThat(repository.refs()).doesNotContainKey("refs/heads/topic");
        }
    }

    @Test
    void validatesClosureAcrossStoredPacksAndRejectsMissingTree() {
        try (NativeGitRepository repository = repository()) {
            ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
            for (String treeId : List.of(tree.toHex(), "f".repeat(40))) {
                ObjectId commit = repository.writeObject(GitObjectType.COMMIT,
                        ("tree " + treeId + "\nauthor A <a@b> 0 +0000\n"
                                + "committer A <a@b> 0 +0000\n\ncommit\n").getBytes(StandardCharsets.UTF_8));
                assertThat(repository.hasCompleteObjectClosure(commit)).isEqualTo(treeId.equals(tree.toHex()));
            }
        }
    }

    @Test
    void repositorySavesFilesToNewBranchAndLoadsThemBack() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");

        repository.saveFiles(
                "main",
                Map.of("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8)),
                "initial acl",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot snapshot =
                repository.loadFiles("main", List.of("orion.xml"));
        assertThat(snapshot.files())
                .containsEntry(
                        "orion.xml",
                        "initial acl".getBytes(StandardCharsets.UTF_8));
        assertThat(repository.refs())
                .containsKey("refs/heads/main");
    }

    @Test
    void preparedFileUpdateDoesNotMoveRefUntilPublished() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");

        NativeGitFileUpdate update = repository.prepareFileUpdate(
                "main",
                Map.of("orion.xml", "prepared acl".getBytes(StandardCharsets.UTF_8)),
                "prepared acl",
                GitCommitAuthor.EMPTY);

        assertThat(repository.refs()).isEmpty();
        assertThat(repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
        assertThat(repository.loadFiles("main", List.of("orion.xml")).files())
                .containsEntry(
                        "orion.xml",
                        "prepared acl".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void repositorySavesFilesOverExistingBranchContent() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");

        repository.saveFiles(
                "main",
                Map.of(
                        "orion.xml",
                        "initial acl".getBytes(StandardCharsets.UTF_8),
                        "nested/acl.xml",
                        "nested acl".getBytes(StandardCharsets.UTF_8)),
                "initial acl",
                GitCommitAuthor.EMPTY);

        repository.saveFiles(
                "main",
                Map.of("orion.xml", "updated acl".getBytes(StandardCharsets.UTF_8)),
                "updated acl",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot snapshot =
                repository.loadFiles("main", List.of("orion.xml", "nested/acl.xml"));
        assertThat(snapshot.files())
                .containsEntry(
                        "orion.xml",
                        "updated acl".getBytes(StandardCharsets.UTF_8))
                .containsEntry(
                        "nested/acl.xml",
                        "nested acl".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conditionalFileSaveRejectsAStaleVersionWithoutReplacingWinningContent() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");
        repository.saveFiles(
                "main",
                Map.of("orion.xml", "version one".getBytes(StandardCharsets.UTF_8)),
                "version one",
                GitCommitAuthor.EMPTY);
        String versionOne = repository.loadFiles("main", List.of("orion.xml")).version().orElseThrow();
        repository.saveFiles(
                "main",
                Map.of(
                        "orion.xml", "version two".getBytes(StandardCharsets.UTF_8),
                        "winner.txt", "winner".getBytes(StandardCharsets.UTF_8)),
                "version two",
                GitCommitAuthor.EMPTY);
        String versionTwo = repository.refs().get("refs/heads/main");

        assertThatThrownBy(() -> repository.saveFilesIfVersion(
                "main",
                versionOne,
                Map.of("orion.xml", "stale".getBytes(StandardCharsets.UTF_8)),
                "stale",
                GitCommitAuthor.EMPTY)).isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(repository.refs().get("refs/heads/main")).isEqualTo(versionTwo);
        assertThat(repository.loadFiles("main", List.of("orion.xml", "winner.txt")).files())
                .containsEntry("orion.xml", "version two".getBytes(StandardCharsets.UTF_8))
                .containsEntry("winner.txt", "winner".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conditionalFileSaveRejectsStaleVersionAcrossProviders(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider firstProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository first = firstProvider.create("demo").valueOrFailure("repository");
        first.saveFiles(
                "main",
                Map.of("orion.xml", "version one".getBytes(StandardCharsets.UTF_8)),
                "version one",
                GitCommitAuthor.EMPTY);
        FileNativeGitRepositoryProvider secondProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository second = secondProvider.find("demo").valueOrFailure("repository");
        String versionOne = second.loadFiles("main", List.of("orion.xml")).version().orElseThrow();
        first.saveFiles(
                "main",
                Map.of(
                        "orion.xml", "version two".getBytes(StandardCharsets.UTF_8),
                        "winner.txt", "winner".getBytes(StandardCharsets.UTF_8)),
                "version two",
                GitCommitAuthor.EMPTY);
        String versionTwo = first.refs().get("refs/heads/main");

        assertThatThrownBy(() -> second.saveFilesIfVersion(
                "main",
                versionOne,
                Map.of("orion.xml", "stale".getBytes(StandardCharsets.UTF_8)),
                "stale",
                GitCommitAuthor.EMPTY)).isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(second.refs().get("refs/heads/main")).isEqualTo(versionTwo);
        assertThat(second.loadFiles("main", List.of("orion.xml", "winner.txt")).files())
                .containsEntry("orion.xml", "version two".getBytes(StandardCharsets.UTF_8))
                .containsEntry("winner.txt", "winner".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conditionalFileSaveBuildsFromExpectedVersionAndPreservesItsOtherFiles() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");
        repository.saveFiles(
                "main",
                Map.of(
                        "orion.xml", "version one".getBytes(StandardCharsets.UTF_8),
                        "preserved.txt", "preserved".getBytes(StandardCharsets.UTF_8)),
                "version one",
                GitCommitAuthor.EMPTY);
        String expectedVersion = repository.loadFiles("main", List.of("orion.xml")).version().orElseThrow();

        repository.saveFilesIfVersion(
                "main",
                expectedVersion,
                Map.of("orion.xml", "version two".getBytes(StandardCharsets.UTF_8)),
                "version two",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot saved = repository.loadFiles(
                "main",
                List.of("orion.xml", "preserved.txt"));
        assertThat(saved.version()).hasValue(repository.refs().get("refs/heads/main"));
        assertThat(saved.version().orElseThrow()).isNotEqualTo(expectedVersion);
        assertThat(saved.files())
                .containsEntry("orion.xml", "version two".getBytes(StandardCharsets.UTF_8))
                .containsEntry("preserved.txt", "preserved".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void repositoryPopulatesDefaultHeadWhenSavingDifferentFirstBranch() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");

        repository.saveFiles(
                "master",
                Map.of("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8)),
                "initial acl",
                GitCommitAuthor.EMPTY);

        assertThat(repository.refs().get("refs/heads/main"))
                .isEqualTo(repository.refs().get("refs/heads/master"));
    }

    private static NativeGitRepository repository() {
        return new NativeGitRepository("demo.git", new GitStorageApi(), "refs/heads/main");
    }
}
