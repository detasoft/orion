package pro.deta.orion.git.compat;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileGitCompatibilityExporterTest {
    @TempDir
    Path root;

    @Test
    void exportsDetachedHeadWithoutAListedBranch() throws Exception {
        Path store = root.resolve("store");
        ObjectId commit;
        try (NativeGitRepository source = new FileNativeGitRepositoryProvider(store)
                .create("demo").valueOrFailure("source repository")) {
            ObjectId tree = source.writeObject(GitObjectType.TREE, new byte[0]);
            commit = source.writeObject(GitObjectType.COMMIT, commit(tree, null, "detached"));
            CommitId target = new CommitId(commit.toBytes());
            source.index().withAccess(index -> {
                index.updateHead(new Head.Detached(target));
                index.apply();
                return null;
            });
        }

        Path output = root.resolve("demo.git");
        new FileGitCompatibilityExporter().export(store, "demo", output);
        try (Repository git = new FileRepositoryBuilder().setGitDir(output.toFile()).build()) {
            assertThat(git.getRefDatabase().exactRef(Constants.HEAD).isSymbolic()).isFalse();
            assertThat(git.resolve(Constants.HEAD)).isNotNull();
            assertThat(git.getRefDatabase().getRefsByPrefix("refs/heads/")).isEmpty();
        }
    }

    @Test
    void exportsUnsortedUnicodeTreeAndRewritesCommitAncestry() throws Exception {
        Path store = root.resolve("store");
        ObjectId originalTree;
        ObjectId originalParent;
        ObjectId originalTip;
        ObjectId blob;
        try (NativeGitRepository source = NativeGitRepositoryProvider.file(store)
                .create("demo").valueOrFailure("source repository")) {
            blob = source.writeObject(GitObjectType.BLOB, "content".getBytes(StandardCharsets.UTF_8));
            originalTree = source.writeObject(GitObjectType.TREE, tree(
                    entry("\uD800\uDC00", blob), entry("\uE000", blob)));
            originalParent = source.writeObject(GitObjectType.COMMIT, commit(originalTree, null, "first"));
            originalTip = source.writeObject(GitObjectType.COMMIT,
                    commit(originalTree, originalParent, "second"));
            source.updateRef("refs/heads/main", "0".repeat(40), originalTip.toHex());
        }

        Path output = root.resolve("demo.git");
        GitCompatibilityExporter exporter = new FileGitCompatibilityExporter();
        exporter.export(store, "demo", output);

        try (Repository git = new FileRepositoryBuilder().setGitDir(output.toFile()).build();
             RevWalk walk = new RevWalk(git)) {
            RevCommit tip = walk.parseCommit(git.resolve("refs/heads/main"));
            RevCommit parent = walk.parseCommit(tip.getParent(0));
            assertThat(tip.getId().name()).isNotEqualTo(originalTip.toHex());
            assertThat(parent.getId().name()).isNotEqualTo(originalParent.toHex());
            assertThat(tip.getTree().getId()).isEqualTo(parent.getTree().getId());
            assertThat(git.open(tip.getTree().getId(), Constants.OBJ_TREE).getBytes())
                    .isEqualTo(tree(entry("\uE000", blob), entry("\uD800\uDC00", blob)));
            assertThat(git.resolve(Constants.HEAD)).isEqualTo(tip.getId());
        }
        String mapping = Files.readString(output.resolve("orion-export/object-map.tsv"));
        assertThat(mapping).contains(originalTree.toHex(), originalParent.toHex(), originalTip.toHex());
    }

    @Test
    void exportsNestedTreeAndAnnotatedTagInGitDirectoryOrder() throws Exception {
        Path store = root.resolve("store");
        ObjectId blob;
        ObjectId originalTag;
        try (NativeGitRepository source = NativeGitRepositoryProvider.file(store)
                .create("demo").valueOrFailure("source repository")) {
            blob = source.writeObject(GitObjectType.BLOB, "content".getBytes(StandardCharsets.UTF_8));
            ObjectId child = source.writeObject(GitObjectType.TREE, tree(
                    entry("\uD800\uDC00", blob), entry("\uE000", blob)));
            ObjectId rootTree = source.writeObject(GitObjectType.TREE, tree(
                    entry("a0", blob), entry("a", 040000, child), entry("a.c", blob)));
            ObjectId commit = source.writeObject(GitObjectType.COMMIT, commit(rootTree, null, "tagged"));
            originalTag = source.writeObject(GitObjectType.TAG, ("object " + commit.toHex()
                    + "\ntype commit\ntag v1\ntagger A <a@example.test> 0 +0000\n\nrelease\n")
                    .getBytes(StandardCharsets.UTF_8));
            source.updateRef("refs/heads/main", "0".repeat(40), commit.toHex());
            source.updateRef("refs/tags/v1", "0".repeat(40), originalTag.toHex());
        }

        Path output = root.resolve("demo.git");
        new FileGitCompatibilityExporter().export(store, "demo", output);

        try (Repository git = new FileRepositoryBuilder().setGitDir(output.toFile()).build()) {
            org.eclipse.jgit.lib.ObjectId commit = git.resolve("refs/heads/main");
            org.eclipse.jgit.lib.ObjectId tag = git.resolve("refs/tags/v1");
            assertThat(tag.name()).isNotEqualTo(originalTag.toHex());
            assertThat(new String(git.open(tag, Constants.OBJ_TAG).getBytes(), StandardCharsets.UTF_8))
                    .startsWith("object " + commit.name() + "\n");
            try (RevWalk walk = new RevWalk(git)) {
                byte[] rootTree = git.open(walk.parseCommit(commit).getTree(), Constants.OBJ_TREE).getBytes();
                assertThat(rootTree).isEqualTo(tree(entry("a.c", blob),
                        entry("a", 040000, new ObjectId(childId(rootTree, "a").name())),
                        entry("a0", blob)));
                assertThat(git.open(childId(rootTree, "a"), Constants.OBJ_TREE).getBytes())
                        .isEqualTo(tree(entry("\uE000", blob), entry("\uD800\uDC00", blob)));
            }
        }
    }

    @Test
    void rejectsSignedCommitThatWouldNeedARewrittenTreeWithoutPublishingOutput() throws Exception {
        Path store = root.resolve("store");
        try (NativeGitRepository source = NativeGitRepositoryProvider.file(store)
                .create("demo").valueOrFailure("source repository")) {
            ObjectId blob = source.writeObject(GitObjectType.BLOB, new byte[]{1});
            ObjectId tree = source.writeObject(GitObjectType.TREE, tree(
                    entry("\uD800\uDC00", blob), entry("\uE000", blob)));
            String signed = "tree " + tree.toHex() + "\n"
                    + "author A <a@example.test> 0 +0000\n"
                    + "committer A <a@example.test> 0 +0000\n"
                    + "gpgsig -----BEGIN PGP SIGNATURE-----\n"
                    + " dummy\n"
                    + " -----END PGP SIGNATURE-----\n\nmessage\n";
            ObjectId commit = source.writeObject(GitObjectType.COMMIT,
                    signed.getBytes(StandardCharsets.UTF_8));
            source.updateRef("refs/heads/main", "0".repeat(40), commit.toHex());
        }
        Path output = root.resolve("demo.git");
        assertThatThrownBy(() -> new FileGitCompatibilityExporter().export(store, "demo", output))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Signed commit");
        assertThat(output).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PGP SIGNATURE", "SSH SIGNATURE", "SIGNED MESSAGE"})
    void rejectsSignedTagWhoseTargetCommitChanges(String signature) throws Exception {
        Path store = root.resolve("store");
        try (NativeGitRepository source = NativeGitRepositoryProvider.file(store)
                .create("demo").valueOrFailure("source repository")) {
            ObjectId blob = source.writeObject(GitObjectType.BLOB, new byte[]{1});
            ObjectId tree = source.writeObject(GitObjectType.TREE, tree(
                    entry("\uD800\uDC00", blob), entry("\uE000", blob)));
            ObjectId commit = source.writeObject(GitObjectType.COMMIT, commit(tree, null, "signed tag"));
            String signed = "object " + commit.toHex() + "\ntype commit\ntag v1\n"
                    + "tagger A <a@example.test> 0 +0000\n\nrelease\n"
                    + "-----BEGIN " + signature + "-----\ndummy\n-----END " + signature + "-----\n";
            ObjectId tag = source.writeObject(GitObjectType.TAG, signed.getBytes(StandardCharsets.UTF_8));
            source.updateRef("refs/heads/main", "0".repeat(40), commit.toHex());
            source.updateRef("refs/tags/v1", "0".repeat(40), tag.toHex());
        }
        Path output = root.resolve("demo.git");
        assertThatThrownBy(() -> new FileGitCompatibilityExporter().export(store, "demo", output))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Signed tag");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsDuplicateNamesEvenWhenGitSortPlacesAnotherEntryBetweenThem() throws Exception {
        Path store = root.resolve("store");
        try (NativeGitRepository source = NativeGitRepositoryProvider.file(store)
                .create("demo").valueOrFailure("source repository")) {
            ObjectId blob = source.writeObject(GitObjectType.BLOB, new byte[]{1});
            ObjectId directory = source.writeObject(GitObjectType.TREE, tree(entry("x", blob)));
            ObjectId rootTree = source.writeObject(GitObjectType.TREE, tree(
                    entry("a", blob), entry("a.c", blob), entry("a", 040000, directory)));
            ObjectId commit = source.writeObject(GitObjectType.COMMIT, commit(rootTree, null, "duplicate"));
            source.updateRef("refs/heads/main", "0".repeat(40), commit.toHex());
        }
        Path output = root.resolve("demo.git");
        assertThatThrownBy(() -> new FileGitCompatibilityExporter().export(store, "demo", output))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("duplicate entry names");
        assertThat(output).doesNotExist();
    }

    @Test
    void preservesAnExistingOutputDirectory() throws Exception {
        Path store = root.resolve("store");
        try (NativeGitRepository ignored = NativeGitRepositoryProvider.file(store)
                .create("demo").valueOrFailure("source repository")) {
            // The output check runs before object conversion.
        }
        Path output = Files.createDirectory(root.resolve("demo.git"));
        Files.writeString(output.resolve("marker"), "existing");

        assertThatThrownBy(() -> new FileGitCompatibilityExporter().export(store, "demo", output))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(output.resolve("marker"))).isEqualTo("existing");
    }

    private static org.eclipse.jgit.lib.ObjectId childId(byte[] tree, String name) {
        byte[] prefix = ("40000 " + name + "\0").getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= tree.length - prefix.length - 20; offset++) {
            boolean found = true;
            for (int index = 0; index < prefix.length; index++) {
                found &= tree[offset + index] == prefix[index];
            }
            if (found) {
                return org.eclipse.jgit.lib.ObjectId.fromRaw(tree, offset + prefix.length);
            }
        }
        throw new AssertionError("Tree does not contain " + name);
    }

    private static byte[] commit(ObjectId tree, ObjectId parent, String message) {
        String header = "tree " + tree.toHex() + "\n"
                + (parent == null ? "" : "parent " + parent.toHex() + "\n")
                + "author A <a@example.test> 0 +0000\n"
                + "committer A <a@example.test> 0 +0000\n\n"
                + message + "\n";
        return header.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] tree(byte[]... entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            output.writeBytes(entry);
        }
        return output.toByteArray();
    }

    private static byte[] entry(String name, ObjectId objectId) {
        return entry(name, 0100644, objectId);
    }

    private static byte[] entry(String name, int mode, ObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((Integer.toOctalString(mode) + " " + name + "\0").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(objectId.toBytes());
        return output.toByteArray();
    }
}
