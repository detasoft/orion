package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.id.RefId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;

import pro.deta.orion.git.parser.v2.data.Head;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import pro.deta.orion.test.integration.git.FileTestSupport;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void normalizesNestedUnicodePathsForReadingAndSaving() throws Exception {
        try (NativeGitRepository repository = repository()) {
            byte[] file = "content".getBytes(StandardCharsets.UTF_8);
            repository.files().withAccess("topic", "create", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("./каталог//файл.txt", file);
                fileAccess.apply();
                return null;
            });
            assertThat(repository.files().readBytes("refs/heads/topic", "каталог/./файл.txt")).isEqualTo(file);
            assertThatThrownBy(() -> repository.files().readBytes("topic", "missing"))
                    .isInstanceOf(GitRepositoryFileNotFoundException.class);
            assertThatThrownBy(() -> repository.files().readBytes("missing", "каталог/файл.txt"))
                    .isInstanceOf(GitRepositoryFileNotFoundException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../file", "nested/../file", "/absolute", "."})
    void rejectsInvalidPathsForReadingAndSaving(String path) throws Exception {
        try (NativeGitRepository repository = repository()) {
            byte[] file = new byte[]{1};
            repository.files().withAccess("main", "create", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("file", file);
                fileAccess.apply();
                return null;
            });
            assertThatThrownBy(() -> repository.files().readBytes("main", path))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.files().withAccess("main", "invalid", GitCommitAuthor.EMPTY,
                    fileAccess -> {
                fileAccess.write(path, file);
                fileAccess.apply();
                return null;
            }))
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
            assertThatThrownBy(() -> repository.files().readBytes("main", "file"))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Malformed tree entry");
            assertThatThrownBy(() -> FileTestSupport.prepared(repository.files(), "main", "update",
                    GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("other", new byte[]{1});
                return null;
            }))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Malformed tree entry");
        }
    }

    @Test
    void rejectsMissingRootTreeForReadingAndSaving() {
        try (NativeGitRepository repository = repository()) {
            ObjectId commit = repository.writeObject(GitObjectType.COMMIT,
                    "author A <a@b> 0 +0000\n\nmissing tree\n".getBytes(StandardCharsets.UTF_8));
            repository.updateRef("refs/heads/main", NULL_ID, commit.toHex());
            assertThatThrownBy(() -> repository.files().readBytes("main", "file"))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Commit is missing root tree");
            assertThatThrownBy(() -> FileTestSupport.prepared(repository.files(), "main", "update",
                    GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("file", new byte[]{1});
                return null;
            }))
                    .isInstanceOf(GitOperationException.class).hasMessageContaining("Commit is missing root tree");
        }
    }

    @Test
    void exposesIdentityAndFreshRefSnapshots() throws Exception {
        try (NativeGitRepository repository = repository()) {
            assertThat(repository.name()).isEqualTo("demo.git");
            assertThat(repository.index().getHEAD()).isEqualTo(new Head.Symbolic(new RefId("refs/heads/main")));
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
            FileTestSupport.Prepared prepared = FileTestSupport.prepared(repository.files(), "configuration",
                    "configuration", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("config.txt", new byte[]{1});
                return null;
            });
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
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));

        repository.files().withAccess("main", "initial acl", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });

        assertThat(repository.files().readBytes("main", "orion.xml"))
                .isEqualTo("initial acl".getBytes(StandardCharsets.UTF_8));
        assertThat(repository.refs())
                .containsKey("refs/heads/main");
    }

    @Test
    void preparedFileUpdateDoesNotMoveRefUntilPublished() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));

        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main", "prepared acl",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "prepared acl".getBytes(StandardCharsets.UTF_8));
            return null;
        });

        assertThat(repository.refs()).isEmpty();
        assertThat(repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
        assertThat(repository.files().readBytes("main", "orion.xml"))
                .isEqualTo("prepared acl".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void repositorySavesFilesOverExistingBranchContent() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));

        repository.files().withAccess("main", "initial acl", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8));
            fileAccess.write("nested/acl.xml", "nested acl".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });

        repository.files().withAccess("main", "updated acl", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "updated acl".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });

        assertThat(repository.files().readBytes("main", "orion.xml"))
                .isEqualTo("updated acl".getBytes(StandardCharsets.UTF_8));
        assertThat(repository.files().readBytes("main", "nested/acl.xml"))
                .isEqualTo("nested acl".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conditionalFileSaveRejectsAStaleVersionWithoutReplacingWinningContent() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));
        repository.files().withAccess("main", "version one", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "version one".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });
        String versionOne = repository.refs().get("refs/heads/main");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main", versionOne,
                "stale", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "stale".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        repository.files().withAccess("main", "version two", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "version two".getBytes(StandardCharsets.UTF_8));
            fileAccess.write("winner.txt", "winner".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });
        String versionTwo = repository.refs().get("refs/heads/main");

        List<RefUpdateResult> results = repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(repository.refs().get("refs/heads/main")).isEqualTo(versionTwo);
        assertThat(repository.files().readBytes("main", "orion.xml"))
                .isEqualTo("version two".getBytes(StandardCharsets.UTF_8));
        assertThat(repository.files().readBytes("main", "winner.txt"))
                .isEqualTo("winner".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conditionalFileSaveRejectsStaleVersionAcrossProviders(@TempDir Path rootDirectory) throws Exception {
        NativeGitRepositoryProvider firstProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository first = firstProvider.create("demo").valueOrFailure("repository");
        first.files().withAccess("main", "version one", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "version one".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });
        NativeGitRepositoryProvider secondProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository second = secondProvider.find("demo").valueOrFailure("repository");
        String versionOne = second.refs().get("refs/heads/main");
        FileTestSupport.Prepared update = FileTestSupport.prepared(second.files(), "main", versionOne, "stale",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "stale".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        first.files().withAccess("main", "version two", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "version two".getBytes(StandardCharsets.UTF_8));
            fileAccess.write("winner.txt", "winner".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });
        String versionTwo = first.refs().get("refs/heads/main");

        List<RefUpdateResult> results = second.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(second.refs().get("refs/heads/main")).isEqualTo(versionTwo);
        assertThat(second.files().readBytes("main", "orion.xml"))
                .isEqualTo("version two".getBytes(StandardCharsets.UTF_8));
        assertThat(second.files().readBytes("main", "winner.txt"))
                .isEqualTo("winner".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conditionalFileSaveBuildsFromExpectedVersionAndPreservesItsOtherFiles() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));
        repository.files().withAccess("main", "version one", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "version one".getBytes(StandardCharsets.UTF_8));
            fileAccess.write("preserved.txt", "preserved".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });
        String expectedVersion = repository.refs().get("refs/heads/main");

        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main", expectedVersion,
                "version two", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "version two".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        assertThat(repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);

        assertThat(repository.refs().get("refs/heads/main")).isNotEqualTo(expectedVersion);
        assertThat(repository.files().readBytes("main", "orion.xml"))
                .isEqualTo("version two".getBytes(StandardCharsets.UTF_8));
        assertThat(repository.files().readBytes("main", "preserved.txt"))
                .isEqualTo("preserved".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void repositoryPopulatesDefaultHeadWhenSavingDifferentFirstBranch() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));

        repository.files().withAccess("master", "initial acl", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });

        assertThat(repository.refs().get("refs/heads/main"))
                .isEqualTo(repository.refs().get("refs/heads/master"));
    }

    private static NativeGitRepository repository() {
        InMemoryStorage storage = new InMemoryStorage();
        return new NativeGitRepository("demo.git", storage, new InMemoryIndex(new RefId("refs/heads/main")));
    }
}
