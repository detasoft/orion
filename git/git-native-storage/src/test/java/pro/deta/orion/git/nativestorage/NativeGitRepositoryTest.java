package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void normalizesNestedUnicodePathsForReadingAndSaving() throws Exception {
        try (NativeGitRepository repository = repository()) {
            GitFile file = GitFile.regular("content".getBytes(StandardCharsets.UTF_8));
            repository.saveFiles("topic", Map.of("./каталог//файл.txt", file), Set.of(),
                    "create", GitCommitAuthor.EMPTY);
            assertThat(repository.loadFiles("refs/heads/topic", List.of("каталог/./файл.txt")).files())
                    .containsExactlyEntriesOf(Map.of("каталог/файл.txt", file));
            assertThatThrownBy(() -> repository.loadFiles("topic", List.of("missing")))
                    .isInstanceOf(GitRepositoryFileNotFoundException.class);
            assertThatThrownBy(() -> repository.loadFiles("missing", List.of("каталог/файл.txt")))
                    .isInstanceOf(GitRepositoryFileNotFoundException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../file", "nested/../file", "/absolute", "."})
    void rejectsInvalidPathsForReadingAndSaving(String path) throws Exception {
        try (NativeGitRepository repository = repository()) {
            GitFile file = GitFile.regular(new byte[]{1});
            repository.saveFiles("main", Map.of("file", file), Set.of(), "create", GitCommitAuthor.EMPTY);
            assertThatThrownBy(() -> repository.loadFiles("main", List.of(path)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.saveFiles("main", Map.of(path, file), Set.of(),
                    "invalid", GitCommitAuthor.EMPTY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid file\0", "100600 file\0", "100644 file\0", "100644 \0"})
    void rejectsMalformedTreeEntriesForReadingAndSaving(String rawTree) {
        try (NativeGitRepository repository = repository()) {
            ObjectId tree = repository.writeObject(GitObjectType.TREE, rawTree.getBytes(StandardCharsets.UTF_8));
            ObjectId commit = repository.writeObject(GitObjectType.COMMIT,
                    ("tree " + tree.toHex() + "\n\nmalformed tree\n").getBytes(StandardCharsets.UTF_8));
            repository.updateRef("refs/heads/main", NULL_ID, commit.toHex());
            assertThatThrownBy(() -> repository.loadFiles("main", List.of("file")))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Malformed tree entry");
            assertThatThrownBy(() -> repository.prepareFileUpdate("main",
                    Map.of("other", GitFile.regular(new byte[]{1})), Set.of(), "update", GitCommitAuthor.EMPTY))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Malformed tree entry");
        }
    }

    @Test
    void rejectsMissingRootTreeForReadingAndSaving() {
        try (NativeGitRepository repository = repository()) {
            ObjectId commit = repository.writeObject(GitObjectType.COMMIT,
                    "author A <a@b> 0 +0000\n\nmissing tree\n".getBytes(StandardCharsets.UTF_8));
            repository.updateRef("refs/heads/main", NULL_ID, commit.toHex());
            assertThatThrownBy(() -> repository.loadFiles("main", List.of("file")))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Commit is missing root tree");
            assertThatThrownBy(() -> repository.prepareFileUpdate("main",
                    Map.of("file", GitFile.regular(new byte[]{1})), Set.of(), "update", GitCommitAuthor.EMPTY))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Commit is missing root tree");
        }
    }

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
                    Map.of("config.txt", GitFile.regular(new byte[]{1})), Set.of(),
                    "configuration", GitCommitAuthor.EMPTY);
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
                Map.of("orion.xml", GitFile.regular("initial acl".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "initial acl",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot snapshot =
                repository.loadFiles("main", List.of("orion.xml"));
        assertThat(snapshot.files())
                .containsEntry(
                        "orion.xml",
                        GitFile.regular("initial acl".getBytes(StandardCharsets.UTF_8)));
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
                Map.of("orion.xml", GitFile.regular("prepared acl".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "prepared acl",
                GitCommitAuthor.EMPTY);

        assertThat(repository.refs()).isEmpty();
        assertThat(repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
        assertThat(repository.loadFiles("main", List.of("orion.xml")).files())
                .containsEntry(
                        "orion.xml",
                        GitFile.regular("prepared acl".getBytes(StandardCharsets.UTF_8)));
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
                        GitFile.regular("initial acl".getBytes(StandardCharsets.UTF_8)),
                        "nested/acl.xml",
                        GitFile.regular("nested acl".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "initial acl",
                GitCommitAuthor.EMPTY);

        repository.saveFiles(
                "main",
                Map.of("orion.xml", GitFile.regular("updated acl".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "updated acl",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot snapshot =
                repository.loadFiles("main", List.of("orion.xml", "nested/acl.xml"));
        assertThat(snapshot.files())
                .containsEntry(
                        "orion.xml",
                        GitFile.regular("updated acl".getBytes(StandardCharsets.UTF_8)))
                .containsEntry(
                        "nested/acl.xml",
                        GitFile.regular("nested acl".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void conditionalFileSaveRejectsAStaleVersionWithoutReplacingWinningContent() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");
        repository.saveFiles(
                "main",
                Map.of("orion.xml", GitFile.regular("version one".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "version one",
                GitCommitAuthor.EMPTY);
        String versionOne = repository.loadFiles("main", List.of("orion.xml")).version().orElseThrow();
        repository.saveFiles(
                "main",
                Map.of(
                        "orion.xml", GitFile.regular("version two".getBytes(StandardCharsets.UTF_8)),
                        "winner.txt", GitFile.regular("winner".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "version two",
                GitCommitAuthor.EMPTY);
        String versionTwo = repository.refs().get("refs/heads/main");

        NativeGitFileUpdate update = repository.prepareFileUpdate(
                "main",
                versionOne,
                Map.of("orion.xml", GitFile.regular("stale".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "stale",
                GitCommitAuthor.EMPTY);
        List<RefUpdateResult> results = repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(repository.refs().get("refs/heads/main")).isEqualTo(versionTwo);
        assertThat(repository.loadFiles("main", List.of("orion.xml", "winner.txt")).files())
                .containsEntry("orion.xml", GitFile.regular("version two".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("winner.txt", GitFile.regular("winner".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void conditionalFileSaveRejectsStaleVersionAcrossProviders(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider firstProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository first = firstProvider.create("demo").valueOrFailure("repository");
        first.saveFiles(
                "main",
                Map.of("orion.xml", GitFile.regular("version one".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "version one",
                GitCommitAuthor.EMPTY);
        FileNativeGitRepositoryProvider secondProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository second = secondProvider.find("demo").valueOrFailure("repository");
        String versionOne = second.loadFiles("main", List.of("orion.xml")).version().orElseThrow();
        first.saveFiles(
                "main",
                Map.of(
                        "orion.xml", GitFile.regular("version two".getBytes(StandardCharsets.UTF_8)),
                        "winner.txt", GitFile.regular("winner".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "version two",
                GitCommitAuthor.EMPTY);
        String versionTwo = first.refs().get("refs/heads/main");

        NativeGitFileUpdate update = second.prepareFileUpdate(
                "main",
                versionOne,
                Map.of("orion.xml", GitFile.regular("stale".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "stale",
                GitCommitAuthor.EMPTY);
        List<RefUpdateResult> results = second.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(second.refs().get("refs/heads/main")).isEqualTo(versionTwo);
        assertThat(second.loadFiles("main", List.of("orion.xml", "winner.txt")).files())
                .containsEntry("orion.xml", GitFile.regular("version two".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("winner.txt", GitFile.regular("winner".getBytes(StandardCharsets.UTF_8)));
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
                        "orion.xml", GitFile.regular("version one".getBytes(StandardCharsets.UTF_8)),
                        "preserved.txt", GitFile.regular("preserved".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "version one",
                GitCommitAuthor.EMPTY);
        String expectedVersion = repository.loadFiles("main", List.of("orion.xml")).version().orElseThrow();

        NativeGitFileUpdate update = repository.prepareFileUpdate(
                "main",
                expectedVersion,
                Map.of("orion.xml", GitFile.regular("version two".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "version two",
                GitCommitAuthor.EMPTY);
        assertThat(repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);

        GitRepositoryFileSnapshot saved = repository.loadFiles(
                "main",
                List.of("orion.xml", "preserved.txt"));
        assertThat(saved.version()).hasValue(repository.refs().get("refs/heads/main"));
        assertThat(saved.version().orElseThrow()).isNotEqualTo(expectedVersion);
        assertThat(saved.files())
                .containsEntry("orion.xml", GitFile.regular("version two".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("preserved.txt", GitFile.regular("preserved".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void repositoryPopulatesDefaultHeadWhenSavingDifferentFirstBranch() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new GitStorageApi(),
                "refs/heads/main");

        repository.saveFiles(
                "master",
                Map.of("orion.xml", GitFile.regular("initial acl".getBytes(StandardCharsets.UTF_8))), Set.of(),
                "initial acl",
                GitCommitAuthor.EMPTY);

        assertThat(repository.refs().get("refs/heads/main"))
                .isEqualTo(repository.refs().get("refs/heads/master"));
    }

    private static NativeGitRepository repository() {
        return new NativeGitRepository("demo.git", new GitStorageApi(), "refs/heads/main");
    }
}
