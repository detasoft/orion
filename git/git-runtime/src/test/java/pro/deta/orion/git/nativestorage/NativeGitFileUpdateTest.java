package pro.deta.orion.git.nativestorage;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import pro.deta.orion.test.integration.git.FileTestSupport;

class NativeGitFileUpdateTest {
    @TempDir
    private Path directory;

    @Test
    void nestedWriteCannotReplaceAnExistingFileWithADirectory() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory()
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, access -> {
                access.write("folder", bytes("original"));
                access.apply();
                return null;
            });
            String initial = repository.refs().get("refs/heads/main");

            assertThatThrownBy(() -> repository.files().withAccess("main", "invalid", GitCommitAuthor.EMPTY,
                    access -> {
                        access.write("folder/child.txt", bytes("new"));
                        access.apply();
                        return null;
                    })).isInstanceOf(IOException.class);

            assertThat(repository.refs()).containsEntry("refs/heads/main", initial);
            assertThat(repository.files().readBytes("main", "folder")).isEqualTo(bytes("original"));
        }
    }

    @Test
    void writingAFileCannotSilentlyReplaceAnExistingDirectory() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory()
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, access -> {
                access.write("folder/keep.txt", bytes("original"));
                access.apply();
                return null;
            });
            String initial = repository.refs().get("refs/heads/main");

            assertThatThrownBy(() -> repository.files().withAccess("main", "invalid", GitCommitAuthor.EMPTY,
                    access -> {
                        access.write("folder", bytes("replacement"));
                        access.apply();
                        return null;
                    })).isInstanceOf(IOException.class);

            assertThat(repository.refs()).containsEntry("refs/heads/main", initial);
            assertThat(repository.files().readBytes("main", "folder/keep.txt"))
                    .isEqualTo(bytes("original"));
        }
    }

    @Test
    void nestedWritePreservesUntouchedSubtreeWithoutPackingItsObjects() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory()
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, access -> {
                access.write("left/config.txt", bytes("before"));
                access.write("right/keep.txt", bytes("keep"));
                access.apply();
                return null;
            });

            repository.files().withAccess("main", "update", GitCommitAuthor.EMPTY, access -> {
                access.write("left/config.txt", bytes("after"));
                assertThat(access.pack().objectCount()).isEqualTo(4);
                access.apply();
                return null;
            });

            assertThat(repository.files().readBytes("main", "left/config.txt")).isEqualTo(bytes("after"));
            assertThat(repository.files().readBytes("main", "right/keep.txt")).isEqualTo(bytes("keep"));
        }
    }

    @Test
    void explicitChangesCanReplaceAFileWithADirectoryAndBack() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory()
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, access -> {
                access.write("entry", bytes("file"));
                access.apply();
                return null;
            });
            repository.files().withAccess("main", "file to directory", GitCommitAuthor.EMPTY, access -> {
                access.delete("entry");
                access.write("entry/child", bytes("nested"));
                access.apply();
                return null;
            });
            assertThat(repository.files().readBytes("main", "entry/child")).isEqualTo(bytes("nested"));

            repository.files().withAccess("main", "directory to file", GitCommitAuthor.EMPTY, access -> {
                access.delete("entry/child");
                access.write("entry", bytes("file again"));
                access.apply();
                return null;
            });
            assertThat(repository.files().readBytes("main", "entry")).isEqualTo(bytes("file again"));
        }
    }

    @Test
    void deletionAndReplacementPublishTogetherAndPreserveOtherFiles() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("nested/remove.txt", new byte[]{1});
                fileAccess.write("keep.txt", new byte[]{2});
                fileAccess.write("change.txt", new byte[]{3});
                fileAccess.apply();
                return null;
            });
            String initial = repository.refs().get("refs/heads/main");
            FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main",
                    "delete and replace", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.delete("nested/remove.txt");
                fileAccess.write("change.txt", new byte[]{4});
                return null;
            });
            assertThat(repository.refs()).containsEntry("refs/heads/main", initial);
            assertThat(repository.publishPack(update.pack(), update.refUpdates(), true,
                    GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            assertThat(repository.files().readBytes("main", "keep.txt")).isEqualTo(new byte[]{2});
            assertThat(repository.files().readBytes("main", "change.txt")).isEqualTo(new byte[]{4});
            assertThatThrownBy(() -> repository.files().readBytes("main", "nested/remove.txt"))
                    .isInstanceOf(GitOperationException.class);
            repository.files().withAccess("main", "delete remaining files", GitCommitAuthor.EMPTY,
                    fileAccess -> {
                fileAccess.delete("keep.txt");
                fileAccess.delete("change.txt");
                fileAccess.apply();
                return null;
            });
            for (String path : List.of("keep.txt", "change.txt")) {
                assertThatThrownBy(() -> repository.files().readBytes("main", path))
                        .isInstanceOf(GitOperationException.class);
            }
        }
    }

    @Test
    void invalidOrConflictingDeletionDoesNotPublishAnything() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory()
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("config.txt", bytes("initial"));
                fileAccess.apply();
                return null;
            });
            Map<String, String> refs = repository.refs();
            repository.index().withAccess(access1 -> {
                Set<PackMetadata> packs = Set.copyOf(access1.packs());
                for (String path : List.of("../config.txt", "./config.txt")) {
                    assertThatThrownBy(() -> repository.files().withAccess("main", "invalid",
                            GitCommitAuthor.EMPTY, fileAccess -> {
                        fileAccess.delete(path);
                        fileAccess.write("config.txt", bytes("changed"));
                        fileAccess.apply();
                        return null;
                    })).isInstanceOf(IllegalArgumentException.class);
                    assertThat(repository.refs()).isEqualTo(refs);
                    assertThat(access1.packs()).containsExactlyInAnyOrderElementsOf(packs);
                }
                return null;
            });
        }
    }

    @Test
    void fileSavePublishesAReadablePack() throws Exception {
        try (NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.file(directory)) {
            NativeGitRepository repository = provider.create("demo").valueOrFailure("repository");
            repository.files().withAccess("main", "first", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("config.txt", bytes("first"));
                fileAccess.apply();
                return null;
            });
            repository.index().withAccess(access -> {
                assertThat(access.packs()).hasSize(1);
                PackMetadata metadata = access.packs().getFirst();
                ByteArrayOutputStream exported = new ByteArrayOutputStream();
                repository.writePack(metadata, new OutputStreamBufferedByteOutput(exported));
                assertThat(Arrays.copyOf(exported.toByteArray(), 4))
                        .isEqualTo("PACK".getBytes(StandardCharsets.US_ASCII));
                return null;
            });
        }
        try (NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.file(directory)) {
            NativeGitRepository reopened = provider.find("demo").valueOrFailure("repository");
            assertThat(reopened.files().readBytes("main", "config.txt")).isEqualTo(bytes("first"));
        }
    }

    @Test
    void staleFileSaveRetainsValidatedPackWithoutChangingTheBranch() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository");
        repository.files().withAccess("main", "first", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("first"));
            fileAccess.apply();
            return null;
        });
        String expected = repository.refs().get("refs/heads/main");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main", expected,
                "stale", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("stale"));
            return null;
        });
        repository.files().withAccess("main", "second", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("second"));
            fileAccess.apply();
            return null;
        });
        String current = repository.refs().get("refs/heads/main");

        List<RefUpdateResult> results = repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(repository.refs()).containsEntry("refs/heads/main", current);
        assertThat(repository.files().readBytes("main", "config.txt"))
                .isEqualTo(bytes("second"));
        repository.index().withAccess(access3 -> {
            assertThat(access3.packs()).hasSize(3);
            assertThat(repository.readObject(update.refUpdates().getFirst().newId().orElseThrow())).isPresent();
            return null;
        });
    }

    @Test
    void preparedPackIsIndependentOfItsReadersAndUnderstoodByJGit() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.inMemory()
                .create("demo").valueOrFailure("repository");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main", "prepared",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("prepared"));
            return null;
        });
        String commitId = update.refUpdates().getFirst().newId().orElseThrow().toHex();
        byte[] firstRead = update.pack();
        firstRead[0] = 0;

        assertThat(repository.refs()).isEmpty();
        assertThat(repository.readObject(new pro.deta.orion.git.parser.v2.id.ObjectId(commitId))).isEmpty();
        try (var target = new InMemoryRepository(new DfsRepositoryDescription("pack consumer"));
             var inserter = target.newObjectInserter()) {
            inserter.newPackParser(new ByteArrayInputStream(update.pack())).parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
            try (var walk = new RevWalk(target)) {
                var commit = walk.parseCommit(ObjectId.fromString(commitId));
                try (var tree = TreeWalk.forPath(target, "config.txt", commit.getTree())) {
                    assertThat(tree).isNotNull();
                    assertThat(target.open(tree.getObjectId(0)).getBytes())
                            .isEqualTo(bytes("prepared"));
                }
            }
        }

        NativeGitRepository another = NativeGitRepositoryProvider.inMemory()
                .create("another").valueOrFailure("repository");
        for (NativeGitRepository target : List.of(repository, another)) {
            assertThat(target.publishPack(update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            assertThat(target.files().readBytes("main", "config.txt"))
                    .isEqualTo(bytes("prepared"));
        }
    }

    @Test
    void corruptOrIncompletePackCannotPublishRefs() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), "main", "prepared",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("prepared"));
            return null;
        });
        byte[] corrupt = update.pack();
        corrupt[corrupt.length - 1] ^= 1;
        byte[] incomplete = Arrays.copyOf(update.pack(), corrupt.length - 1);

        for (byte[] rejected : List.of(corrupt, incomplete)) {
            assertThatThrownBy(() -> repository.publishPack(
                    rejected, update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .isInstanceOf(GitOperationException.class);
            assertThat(repository.refs()).isEmpty();
            repository.index().withAccess(access4 -> {
                assertThat(access4.packs()).isEmpty();
                return null;
            });
        }
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
