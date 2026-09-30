package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
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
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static pro.deta.orion.git.parser.v2.data.FileMode.EXECUTABLE_FILE;
import static pro.deta.orion.git.parser.v2.data.FileMode.SYMLINK;

class NativeGitFileModesTest {
    @TempDir
    Path directory;

    @Test
    void savesExplicitModesForNewFilesAndUpdatesIncludingModeOnlyChanges() throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository");
        byte[] script = bytes("#!/bin/sh\nexit 0\n");
        Map<String, GitFile> initial = Map.of(
                "run.sh", new GitFile(EXECUTABLE_FILE, script),
                "next.sh", GitFile.regular(script),
                "link", new GitFile(SYMLINK, bytes("run.sh")));
        repository.files().saveFiles("main", initial, Set.of(), "create", GitCommitAuthor.EMPTY);
        List<String> paths = List.of("run.sh", "next.sh", "link");
        assertThat(repository.files().loadFiles("main", paths).files()).isEqualTo(initial);

        Map<String, GitFile> updated = Map.of(
                "run.sh", GitFile.regular(bytes("updated")),
                "next.sh", new GitFile(EXECUTABLE_FILE, script),
                "link", new GitFile(SYMLINK, bytes("next.sh")));
        repository.files().saveFiles("main", updated, Set.of(), "update", GitCommitAuthor.EMPTY);

        NativeGitRepository reopened = new FileNativeGitRepositoryProvider(directory)
                .find("demo").valueOrFailure("repository");
        assertThat(reopened.files().loadFiles("main", paths).files()).isEqualTo(updated);
        try (InMemoryRepository observed = new InMemoryRepository(new DfsRepositoryDescription())) {
            copyPacks(reopened, observed);
            try (RevWalk walk = new RevWalk(observed)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(reopened.refs().get("refs/heads/main")));
                RevCommit parent = walk.parseCommit(commit.getParent(0));
                try (TreeWalk original = TreeWalk.forPath(observed, "next.sh", parent.getTree())) {
                    assertThat(original.getFileMode(0)).isEqualTo(FileMode.REGULAR_FILE);
                    assertEntry(observed, commit, "next.sh", FileMode.EXECUTABLE_FILE, original.getObjectId(0));
                }
                for (Map.Entry<String, GitFile> entry : updated.entrySet()) {
                    try (TreeWalk tree = TreeWalk.forPath(observed, entry.getKey(), commit.getTree())) {
                        assertThat(tree.getRawMode(0)).isEqualTo(entry.getValue().mode().code());
                        assertThat(observed.open(tree.getObjectId(0)).getBytes())
                                .isEqualTo(entry.getValue().content());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesUntouchedModesAndIdsAcrossFileSaveAndReopen(boolean withGitlink) throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
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

        assertThatCode(() -> repository.files().saveFiles(
                "main", Map.of("config.txt", GitFile.regular(bytes("updated"))), Set.of(),
                "update", GitCommitAuthor.EMPTY)).doesNotThrowAnyException();

        NativeGitRepository reopened = new FileNativeGitRepositoryProvider(directory)
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
        Map<String, GitFile> files = Map.of(
                "\uE000", new GitFile(EXECUTABLE_FILE, bytes("private-use name")),
                "\uD800\uDC00", GitFile.regular(bytes("supplementary name")),
                "nested/\uE000", GitFile.regular(bytes("nested private-use name")),
                "nested/\uD800\uDC00", new GitFile(SYMLINK, bytes("../\uE000")),
                "a.c", GitFile.regular(bytes("sibling before directory")),
                "a/x", GitFile.regular(bytes("nested sibling")),
                "a0", GitFile.regular(bytes("sibling after directory")));
        List<String> paths = new ArrayList<>(files.keySet());
        Map<String, GitFile> expected = new LinkedHashMap<>(files);
        GitFile updated = GitFile.regular(bytes("updated nested sibling"));
        expected.put("a/x", updated);
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().saveFiles("main", files, Set.of(), "create", GitCommitAuthor.EMPTY);
            assertGitTreeOrdering(repository);
            assertThat(repository.files().loadFiles("main", paths).files()).isEqualTo(files);

            repository.files().saveFiles(
                    "main", Map.of("a/x", updated), Set.of(), "update", GitCommitAuthor.EMPTY);
            assertGitTreeOrdering(repository);
            assertThat(repository.files().loadFiles("main", paths).files()).isEqualTo(expected);
        }

        try (NativeGitRepository reopened = new FileNativeGitRepositoryProvider(directory)
                .find("demo").valueOrFailure("repository")) {
            assertThat(reopened.files().loadFiles("main", paths).files()).isEqualTo(expected);
        }
    }

    @Test
    void preservesImportedUnicodeFilesWhenSavingAnotherFile() throws Exception {
        GitFile executable = new GitFile(EXECUTABLE_FILE, bytes("executable"));
        GitFile link = new GitFile(SYMLINK, bytes("\uE000"));
        Map<String, GitFile> files = Map.of(
                "\uE000", executable, "\uD800\uDC00", link,
                "nested/\uE000", executable, "nested/\uD800\uDC00", link);
        GitFile configuration = GitFile.regular(bytes("updated configuration"));
        Map<String, GitFile> expected = new LinkedHashMap<>(files);
        expected.put("config.txt", configuration);
        List<String> paths = new ArrayList<>(expected.keySet());
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            ObjectId executableId = write(repository, GitObjectType.BLOB, executable.content());
            ObjectId linkId = write(repository, GitObjectType.BLOB, link.content());
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
            assertThat(repository.files().loadFiles(
                    "main", new ArrayList<>(files.keySet())).files()).isEqualTo(files);

            repository.files().saveFiles("main", Map.of("config.txt", configuration), Set.of(),
                    "update", GitCommitAuthor.EMPTY);
            assertGitTreeOrdering(repository);
            assertThat(repository.files().loadFiles("main", paths).files()).isEqualTo(expected);
        }

        try (NativeGitRepository reopened = new FileNativeGitRepositoryProvider(directory)
                .find("demo").valueOrFailure("repository")) {
            assertThat(reopened.files().loadFiles("main", paths).files()).isEqualTo(expected);
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
