package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.fileapi.GitFileAccess;

import pro.deta.orion.util.VcChangeDescription;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.util.VcChangeAuthor;
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
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    @TempDir
    private Path directory;

    @Test
    void nestedWriteCannotReplaceAnExistingFileWithADirectory() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.write("folder", bytes("original"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            String initial = repository.refs().get("refs/heads/main");

            assertThatThrownBy(
                            () -> {
                                FileTestSupport.initialize(repository, "main");
                                try (GitFileAccess access =
                                        repository.files().open(repository.index().resolve(repository.ref("main")))) {
                                    access.write("folder/child.txt", bytes("new"));
                                    repository.refUpdate(
                                            repository.ref("main"),
                                            access.revision(),
                                            access.commit(
                                                    new VcChangeDescription(
                                                            VcChangeAuthor.EMPTY, "invalid")));
                                }
                            })
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(IOException.class);

            assertThat(repository.refs()).containsEntry("refs/heads/main", initial);
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("folder")))
                    .isEqualTo(bytes("original"));
        }
    }

    @Test
    void writingAFileCannotSilentlyReplaceAnExistingDirectory() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.write("folder/keep.txt", bytes("original"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            String initial = repository.refs().get("refs/heads/main");

            assertThatThrownBy(
                            () -> {
                                FileTestSupport.initialize(repository, "main");
                                try (GitFileAccess access =
                                        repository.files().open(repository.index().resolve(repository.ref("main")))) {
                                    access.write("folder", bytes("replacement"));
                                    repository.refUpdate(
                                            repository.ref("main"),
                                            access.revision(),
                                            access.commit(
                                                    new VcChangeDescription(
                                                            VcChangeAuthor.EMPTY, "invalid")));
                                }
                            })
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(IOException.class);

            assertThat(repository.refs()).containsEntry("refs/heads/main", initial);
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("folder/keep.txt")))
                    .isEqualTo(bytes("original"));
        }
    }

    @Test
    void nestedWritePreservesUntouchedSubtreeWithoutPackingItsObjects() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.write("left/config.txt", bytes("before"));
                access.write("right/keep.txt", bytes("keep"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.write("left/config.txt", bytes("after"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "update")));
                pro.deta.orion.git.parser.v2.id.ObjectId created =
                        new pro.deta.orion.git.parser.v2.id.ObjectId(
                                repository.refs().get("refs/heads/main"));
                long count =
                        repository
                                .index()
                                .withAccess(
                                        index ->
                                                index.findPack(
                                                                index.locations(created)
                                                                        .getFirst()
                                                                        .packId())
                                                        .orElseThrow()
                                                        .objectCount());
                assertThat(count).isEqualTo(4);
            }

            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("left/config.txt")))
                    .isEqualTo(bytes("after"));
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("right/keep.txt")))
                    .isEqualTo(bytes("keep"));
        }
    }

    @Test
    void explicitChangesCanReplaceAFileWithADirectoryAndBack() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.write("entry", bytes("file"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.delete("entry");
                access.write("entry/child", bytes("nested"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(
                                new VcChangeDescription(
                                        VcChangeAuthor.EMPTY, "file to directory")));
            }

            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("entry/child")))
                    .isEqualTo(bytes("nested"));

            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                access.delete("entry/child");
                access.write("entry", bytes("file again"));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(
                                new VcChangeDescription(
                                        VcChangeAuthor.EMPTY, "directory to file")));
            }

            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("entry")))
                    .isEqualTo(bytes("file again"));
        }
    }

    @Test
    void deletionAndReplacementPublishTogetherAndPreserveOtherFiles() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess fileAccess = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                fileAccess.write("nested/remove.txt", new byte[] {1});
                fileAccess.write("keep.txt", new byte[] {2});
                fileAccess.write("change.txt", new byte[] {3});
                repository.refUpdate(
                        repository.ref("main"),
                        fileAccess.revision(),
                        fileAccess.commit(
                                new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            String initial = repository.refs().get("refs/heads/main");
            FileTestSupport.Prepared update = FileTestSupport.prepared(repository, "main",
                    "delete and replace", VcChangeAuthor.EMPTY, fileAccess -> {
                fileAccess.delete("nested/remove.txt");
                fileAccess.write("change.txt", new byte[]{4});
                return null;
            });
            assertThat(repository.refs()).containsEntry("refs/heads/main", initial);
            assertThat(repository.publishPack(update.pack(), update.refUpdates(), true,
                    GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("keep.txt")))
                    .isEqualTo(new byte[] {2});
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.index().resolve(repository.ref("main")),
                                            file -> file.readBytes("change.txt")))
                    .isEqualTo(new byte[] {4});
            assertThatThrownBy(
                            () ->
                                    repository
                                            .files()
                                            .read(
                                                    repository
                                                            .index()
                                                            .resolve(repository.ref("main")),
                                                    file -> file.readBytes("nested/remove.txt")))
                    .isInstanceOf(GitOperationException.class);
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess fileAccess = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                fileAccess.delete("keep.txt");
                fileAccess.delete("change.txt");
                repository.refUpdate(
                        repository.ref("main"),
                        fileAccess.revision(),
                        fileAccess.commit(
                                new VcChangeDescription(
                                        VcChangeAuthor.EMPTY, "delete remaining files")));
            }

            for (String path : List.of("keep.txt", "change.txt")) {
                assertThatThrownBy(
                                () ->
                                        repository
                                                .files()
                                                .read(
                                                        repository
                                                                .index()
                                                                .resolve(repository.ref("main")),
                                                        file -> file.readBytes(path)))
                        .isInstanceOf(GitOperationException.class);
            }
        }
    }

    @Test
    void invalidOrConflictingDeletionDoesNotPublishAnything() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess fileAccess = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                fileAccess.write("config.txt", bytes("initial"));
                repository.refUpdate(
                        repository.ref("main"),
                        fileAccess.revision(),
                        fileAccess.commit(
                                new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            Map<String, String> refs = repository.refs();
            repository
                    .index()
                    .withAccess(
                            access1 -> {
                                Set<PackMetadata> packs = Set.copyOf(access1.packs());
                                for (String path : List.of("../config.txt", "./config.txt")) {
                                    assertThatThrownBy(
                                                    () -> {
                                                        FileTestSupport.initialize(
                                                                repository, "main");
                                                        try (GitFileAccess fileAccess =
                                                                repository
                                                                        .files()
                                                                        .open(
                                                                                repository.index().resolve(repository.ref(
                                                                                        "main")))) {
                                                            fileAccess.delete(path);
                                                            fileAccess.write(
                                                                    "config.txt", bytes("changed"));
                                                            repository.refUpdate(
                                                                    repository.ref("main"),
                                                                    fileAccess.revision(),
                                                                    fileAccess.commit(
                                                                            new VcChangeDescription(
                                                                                    VcChangeAuthor
                                                                                            .EMPTY,
                                                                                    "invalid")));
                                                        }
                                                    })
                                            .isInstanceOf(IllegalArgumentException.class);
                                    assertThat(repository.refs()).isEqualTo(refs);
                                    assertThat(access1.packs())
                                            .containsExactlyInAnyOrderElementsOf(packs);
                                }
                                return null;
                            });
        }
    }

    @Test
    void fileSavePublishesAReadablePack() throws Exception {
        try (NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(directory, testRuntime.events())) {
            NativeGitRepository repository = provider.create("demo").valueOrFailure("repository");
            FileTestSupport.initialize(repository, "main");
            try (GitFileAccess fileAccess = repository.files().open(repository.index().resolve(repository.ref("main")))) {
                fileAccess.write("config.txt", bytes("first"));
                repository.refUpdate(
                        repository.ref("main"),
                        fileAccess.revision(),
                        fileAccess.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "first")));
            }

            repository.index().withAccess(access -> {
                assertThat(access.packs()).hasSize(3);
                PackMetadata metadata = access.packs().getFirst();
                ByteArrayOutputStream exported = new ByteArrayOutputStream();
                repository.writePack(metadata, new OutputStreamBufferedByteOutput(exported));
                assertThat(Arrays.copyOf(exported.toByteArray(), 4))
                        .isEqualTo("PACK".getBytes(StandardCharsets.US_ASCII));
                return null;
            });
        }
        try (NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(directory, testRuntime.events())) {
            NativeGitRepository reopened = provider.find("demo").valueOrFailure("repository");
            assertThat(
                            reopened.files()
                                    .<byte[], Exception>read(
                                            reopened.index().resolve(reopened.ref("main")),
                                            file -> file.readBytes("config.txt")))
                    .isEqualTo(bytes("first"));
        }
    }

    @Test
    void staleFileSaveRetainsValidatedPackWithoutChangingTheBranch() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository");
        FileTestSupport.initialize(repository, "main");
        try (GitFileAccess fileAccess = repository.files().open(repository.index().resolve(repository.ref("main")))) {
            fileAccess.write("config.txt", bytes("first"));
            repository.refUpdate(
                    repository.ref("main"),
                    fileAccess.revision(),
                    fileAccess.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "first")));
        }

        String expected = repository.refs().get("refs/heads/main");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository, "main", expected,
                "stale", VcChangeAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("stale"));
            return null;
        });
        FileTestSupport.initialize(repository, "main");
        try (GitFileAccess fileAccess = repository.files().open(repository.index().resolve(repository.ref("main")))) {
            fileAccess.write("config.txt", bytes("second"));
            repository.refUpdate(
                    repository.ref("main"),
                    fileAccess.revision(),
                    fileAccess.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "second")));
        }

        String current = repository.refs().get("refs/heads/main");

        List<RefUpdateResult> results = repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(repository.refs()).containsEntry("refs/heads/main", current);
        assertThat(
                        repository
                                .files()
                                .<byte[], Exception>read(
                                        repository.index().resolve(repository.ref("main")),
                                        file -> file.readBytes("config.txt")))
                .isEqualTo(bytes("second"));
        repository.index().withAccess(access3 -> {
            assertThat(access3.packs()).hasSize(5);
            assertThat(repository.readObject(update.refUpdates().getFirst().newId().orElseThrow())).isPresent();
            return null;
        });
    }

    @Test
    void preparedPackIsIndependentOfItsReadersAndUnderstoodByJGit() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository, "main", "prepared",
                VcChangeAuthor.EMPTY, fileAccess -> {
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

        NativeGitRepository another = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("another").valueOrFailure("repository");
        for (NativeGitRepository target : List.of(repository, another)) {
            assertThat(target.publishPack(update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            assertThat(
                            target.files()
                                    .<byte[], Exception>read(
                                            target.index().resolve(target.ref("main")),
                                            file -> file.readBytes("config.txt")))
                    .isEqualTo(bytes("prepared"));
        }
    }

    @Test
    void corruptOrIncompletePackCannotPublishRefs() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository");
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository, "main", "prepared",
                VcChangeAuthor.EMPTY, fileAccess -> {
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
