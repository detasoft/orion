package pro.deta.orion.git.nativestorage;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static pro.deta.orion.git.parser.v2.data.FileMode.EXECUTABLE_FILE;
import static pro.deta.orion.git.parser.v2.data.FileMode.SYMLINK;

class NativeGitFileModesTest {
    @TempDir
    Path directory;

    @Test
    void savesExplicitModesForNewFilesAndUpdatesIncludingModeOnlyChanges() throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository");
        byte[] script = bytes("#!/bin/sh\nexit 0\n");
        Map<String, byte[]> initial = Map.of(
                "run.sh", script,
                "next.sh", script,
                "link", bytes("run.sh"));
        repository.files().withAccess("main", "create", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("run.sh", EXECUTABLE_FILE, script);
            fileAccess.write("next.sh", script);
            fileAccess.write("link", SYMLINK, bytes("run.sh"));
            fileAccess.apply();
            return null;
        });
        assertContents(repository, initial);

        Map<String, byte[]> updated = Map.of(
                "run.sh", bytes("updated"),
                "next.sh", script,
                "link", bytes("next.sh"));
        repository.files().withAccess("main", "update", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("run.sh", bytes("updated"));
            fileAccess.write("next.sh", EXECUTABLE_FILE, script);
            fileAccess.write("link", SYMLINK, bytes("next.sh"));
            fileAccess.apply();
            return null;
        });

        NativeGitRepository reopened = NativeGitRepositoryProvider.file(directory)
                .find("demo").valueOrFailure("repository");
        assertContents(reopened, updated);
        try (InMemoryRepository observed = new InMemoryRepository(new DfsRepositoryDescription())) {
            copyPacks(reopened, observed);
            try (RevWalk walk = new RevWalk(observed)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(reopened.refs().get("refs/heads/main")));
                RevCommit parent = walk.parseCommit(commit.getParent(0));
                try (TreeWalk original = TreeWalk.forPath(observed, "next.sh", parent.getTree())) {
                    assertThat(original.getFileMode(0)).isEqualTo(FileMode.REGULAR_FILE);
                    assertEntry(observed, commit, "next.sh", FileMode.EXECUTABLE_FILE, original.getObjectId(0));
                }
                for (Map.Entry<String, byte[]> entry : updated.entrySet()) {
                    try (TreeWalk tree = TreeWalk.forPath(observed, entry.getKey(), commit.getTree())) {
                        FileMode expectedMode = switch (entry.getKey()) {
                            case "next.sh" -> FileMode.EXECUTABLE_FILE;
                            case "link" -> FileMode.SYMLINK;
                            default -> FileMode.REGULAR_FILE;
                        };
                        assertThat(tree.getFileMode(0)).isEqualTo(expectedMode);
                        assertThat(observed.open(tree.getObjectId(0)).getBytes())
                                .isEqualTo(entry.getValue());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesUntouchedModesAndIdsAcrossFileSaveAndReopen(boolean withGitlink) throws Exception {
        NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository");
        ObjectId executable = write(repository, GitObjectType.BLOB, bytes("#!/bin/sh\nexit 0\n"));
        ObjectId link = write(repository, GitObjectType.BLOB, bytes("run.sh"));
        ObjectId module = ObjectId.fromString("1".repeat(40));
        TreeFormatter nested = new TreeFormatter();
        nested.append("link", FileMode.SYMLINK, link);
        if (withGitlink) {
            nested.append("module", FileMode.GITLINK, module);
        }
        nested.append("run.sh", FileMode.EXECUTABLE_FILE, executable);
        ObjectId nestedId = write(repository, GitObjectType.TREE, nested.toByteArray());
        TreeFormatter root = new TreeFormatter();
        root.append("nested", FileMode.TREE, nestedId);
        ObjectId tree = write(repository, GitObjectType.TREE, root.toByteArray());
        ObjectId initial = write(repository, GitObjectType.COMMIT, bytes("tree " + tree.name()
                + "\nauthor A <a@test> 0 +0000\ncommitter A <a@test> 0 +0000\n\ninitial\n"));
        assertThat(repository.updateRef("refs/heads/main", "0".repeat(40), initial.name()).status())
                .isEqualTo(RefUpdateResult.Status.APPLIED);

        assertThatCode(() -> repository.files().withAccess("main", "update", GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.write("config.txt", bytes("updated"));
            fileAccess.apply();
            return null;
        })).doesNotThrowAnyException();

        NativeGitRepository reopened = NativeGitRepositoryProvider.file(directory)
                .find("demo").valueOrFailure("repository");
        try (InMemoryRepository observed = new InMemoryRepository(new DfsRepositoryDescription())) {
            copyPacks(reopened, observed);
            try (RevWalk walk = new RevWalk(observed)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(reopened.refs().get("refs/heads/main")));
                assertEntry(observed, commit, "nested/run.sh", FileMode.EXECUTABLE_FILE, executable);
                assertEntry(observed, commit, "nested/link", FileMode.SYMLINK, link);
                if (withGitlink) {
                    assertEntry(observed, commit, "nested/module", FileMode.GITLINK, module);
                }
                assertEntry(observed, commit, "nested", FileMode.TREE, nestedId);
                assertThat(commit.getParent(0).getId()).isEqualTo(initial);
            }
        }
    }

    @Test
    void preservesUnicodeFilesAcrossInternalSaveUpdateAndReopen() throws Exception {
        Map<String, byte[]> files = Map.of(
                "\uE000", bytes("private-use name"),
                "\uD800\uDC00", bytes("supplementary name"),
                "nested/\uE000", bytes("nested private-use name"),
                "nested/\uD800\uDC00", bytes("../\uE000"),
                "a.c", bytes("sibling before directory"),
                "a/x", bytes("nested sibling"),
                "a0", bytes("sibling after directory"));
        Map<String, byte[]> expected = new LinkedHashMap<>(files);
        byte[] updated = bytes("updated nested sibling");
        expected.put("a/x", updated);
        Map<String, FileMode> modes = Map.of("\uE000", FileMode.EXECUTABLE_FILE,
                "nested/\uD800\uDC00", FileMode.SYMLINK);
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "create", GitCommitAuthor.EMPTY, fileAccess -> {
                for (Map.Entry<String, byte[]> fileEntry : files.entrySet()) {
                    pro.deta.orion.git.parser.v2.data.FileMode mode = switch (fileEntry.getKey()) {
                        case "\uE000" -> EXECUTABLE_FILE;
                        case "nested/\uD800\uDC00" -> SYMLINK;
                        default -> pro.deta.orion.git.parser.v2.data.FileMode.REGULAR_FILE;
                    };
                    fileAccess.write(fileEntry.getKey(), mode, fileEntry.getValue());
                }
                fileAccess.apply();
                return null;
            });
            assertGitTreeOrdering(repository);
            assertContents(repository, files, modes);

            repository.files().withAccess("main", "update", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("a/x", updated);
                fileAccess.apply();
                return null;
            });
            assertGitTreeOrdering(repository);
            assertContents(repository, expected, modes);
        }

        try (NativeGitRepository reopened = NativeGitRepositoryProvider.file(directory)
                .find("demo").valueOrFailure("repository")) {
            assertContents(reopened, expected, modes);
        }
    }

    @Test
    void preservesImportedUnicodeFilesWhenSavingAnotherFile() throws Exception {
        byte[] executable = bytes("executable");
        byte[] link = bytes("\uE000");
        Map<String, byte[]> files = Map.of(
                "\uE000", executable, "\uD800\uDC00", link,
                "nested/\uE000", executable, "nested/\uD800\uDC00", link);
        byte[] configuration = bytes("updated configuration");
        Map<String, byte[]> expected = new LinkedHashMap<>(files);
        expected.put("config.txt", configuration);
        Map<String, FileMode> modes = Map.of(
                "\uE000", FileMode.EXECUTABLE_FILE, "nested/\uE000", FileMode.EXECUTABLE_FILE,
                "\uD800\uDC00", FileMode.SYMLINK, "nested/\uD800\uDC00", FileMode.SYMLINK);
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory)
                .create("demo").valueOrFailure("repository")) {
            ObjectId executableId = write(repository, GitObjectType.BLOB, executable);
            ObjectId linkId = write(repository, GitObjectType.BLOB, link);
            TreeFormatter nested = new TreeFormatter();
            nested.append("\uE000", FileMode.EXECUTABLE_FILE, executableId);
            nested.append("\uD800\uDC00", FileMode.SYMLINK, linkId);
            ObjectId nestedId = write(repository, GitObjectType.TREE, nested.toByteArray());
            TreeFormatter root = new TreeFormatter();
            root.append("nested", FileMode.TREE, nestedId);
            root.append("\uE000", FileMode.EXECUTABLE_FILE, executableId);
            root.append("\uD800\uDC00", FileMode.SYMLINK, linkId);
            ObjectId tree = write(repository, GitObjectType.TREE, root.toByteArray());
            ObjectId initial = write(repository, GitObjectType.COMMIT, bytes("tree " + tree.name()
                    + "\nauthor A <a@test> 0 +0000\ncommitter A <a@test> 0 +0000\n\nimported\n"));
            assertThat(repository.updateRef("refs/heads/main", "0".repeat(40), initial.name()).status())
                    .isEqualTo(RefUpdateResult.Status.APPLIED);
            assertContents(repository, files, modes);

            repository.files().withAccess("main", "update", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("config.txt", configuration);
                fileAccess.apply();
                return null;
            });
            assertGitTreeOrdering(repository);
            assertContents(repository, expected, modes);
        }

        try (NativeGitRepository reopened = NativeGitRepositoryProvider.file(directory)
                .find("demo").valueOrFailure("repository")) {
            assertContents(reopened, expected, modes);
        }
    }

    private static void assertContents(NativeGitRepository repository, Map<String, byte[]> expected)
            throws Exception {
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertThat(repository.files().readBytes("main", entry.getKey())).isEqualTo(entry.getValue());
        }
    }

    private static void assertContents(NativeGitRepository repository, Map<String, byte[]> expected,
            Map<String, FileMode> modes) throws Exception {
        assertContents(repository, expected);
        try (InMemoryRepository observed = new InMemoryRepository(new DfsRepositoryDescription())) {
            copyPacks(repository, observed);
            try (RevWalk walk = new RevWalk(observed)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(repository.refs().get("refs/heads/main")));
                for (String path : expected.keySet()) {
                    try (TreeWalk tree = TreeWalk.forPath(observed, path, commit.getTree())) {
                        assertThat(tree.getFileMode(0)).isEqualTo(modes.getOrDefault(path, FileMode.REGULAR_FILE));
                    }
                }
            }
        }
    }

    private static void copyPacks(NativeGitRepository source, InMemoryRepository destination) throws Exception {
        try (ObjectInserter inserter = destination.newObjectInserter()) {
            source.index().withAccess(access1 -> {
                for (PackMetadata metadata : access1.packs()) {
                    ByteArrayOutputStream exported = new ByteArrayOutputStream();
                    source.writePack(metadata, new OutputStreamBufferedByteOutput(exported));
                    byte[] pack = exported.toByteArray();
                    inserter.newPackParser(new ByteArrayInputStream(pack)).parse(NullProgressMonitor.INSTANCE);
                }
                inserter.flush();
                return null;
            });
        }
    }

    private static void assertGitTreeOrdering(NativeGitRepository source) throws Exception {
        try (InMemoryRepository observed = new InMemoryRepository(new DfsRepositoryDescription())) {
            copyPacks(source, observed);
            try (RevWalk revisions = new RevWalk(observed);
                 TreeWalk trees = new TreeWalk(observed)) {
                RevCommit commit = revisions.parseCommit(ObjectId.fromString(source.refs().get("refs/heads/main")));
                ObjectChecker checker = new ObjectChecker();
                assertThatCode(() -> checker.check(Constants.OBJ_TREE,
                        observed.open(commit.getTree()).getBytes())).doesNotThrowAnyException();
                trees.addTree(commit.getTree());
                while (trees.next()) {
                    if (trees.isSubtree()) {
                        assertThatCode(() -> checker.check(Constants.OBJ_TREE,
                                observed.open(trees.getObjectId(0)).getBytes())).doesNotThrowAnyException();
                        trees.enterSubtree();
                    }
                }
            }
        }
    }

    private static void assertEntry(InMemoryRepository repository, RevCommit commit, String path,
            FileMode mode, ObjectId id) throws Exception {
        try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
            assertThat(tree).isNotNull();
            assertThat(tree.getFileMode(0)).isEqualTo(mode);
            assertThat(tree.getObjectId(0)).isEqualTo(id);
        }
    }

    private static ObjectId write(NativeGitRepository repository, GitObjectType type, byte[] content) {
        return ObjectId.fromString(repository.writeObject(type, content).toHex());
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
