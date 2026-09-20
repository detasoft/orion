package pro.deta.orion.git.parser.v2.read;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.store;

class GitObjectGraphTest {
    @AfterEach
    void closeStorage() throws Exception {
        objects.close();
    }

    private final GitStorageApi objects = new GitStorageApi();
    private final GitObjectGraph graph = new GitObjectGraph(objects);

    @Test
    void traversesCommitTreeAndBlob() throws Exception {
        ObjectId blob = store(objects, GitObjectType.BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
        ObjectId tree = store(objects, GitObjectType.TREE, treeEntry("100644", "hello.txt", blob));
        ObjectId commit = store(objects,
                GitObjectType.COMMIT,
                ("tree " + tree + "\n"
                        + "author Test <test@example.com> 0 +0000\n"
                        + "committer Test <test@example.com> 0 +0000\n"
                        + "\ninitial\n").getBytes(StandardCharsets.UTF_8));

        Set<ObjectId> result =
                graph.reachableObjects(Set.of(commit), false);

        assertThat(result)
                .containsExactlyInAnyOrder(commit, tree, blob);
    }

    @Test
    void excludesObjectsReachableFromHaves() throws Exception {
        ObjectId baseBlob = store(objects, GitObjectType.BLOB, "base\n".getBytes(StandardCharsets.UTF_8));
        ObjectId baseTree = store(objects, GitObjectType.TREE, treeEntry("100644", "file.txt", baseBlob));
        ObjectId baseCommit = writeCommit(baseTree, null, "base");

        ObjectId tipBlob = store(objects, GitObjectType.BLOB, "tip\n".getBytes(StandardCharsets.UTF_8));
        ObjectId tipTree = store(objects, GitObjectType.TREE, treeEntry("100644", "file.txt", tipBlob));
        ObjectId tipCommit = writeCommit(tipTree, baseCommit, "tip");

        Set<ObjectId> result =
                graph.reachableObjects(Set.of(tipCommit), false);
        result.removeAll(graph.reachableObjects(Set.of(baseCommit), true));

        assertThat(result)
                .containsExactlyInAnyOrder(tipCommit, tipTree, tipBlob);
    }

    @Test
    void followsAnnotatedTagTargets() throws Exception {
        ObjectId blob = store(objects,
                GitObjectType.BLOB,
                "tagged\n".getBytes(StandardCharsets.UTF_8));
        ObjectId tree = store(objects,
                GitObjectType.TREE,
                treeEntry("100644", "tagged.txt", blob));
        ObjectId commit = writeCommit(tree, null, "tagged");
        ObjectId tag = writeTag(commit, "v1");

        Set<ObjectId> result =
                graph.reachableObjects(Set.of(tag), false);

        assertThat(result)
                .containsExactlyInAnyOrder(tag, commit, tree, blob);
    }

    @Test
    void ignoresUnknownHaveRoots() throws Exception {
        ObjectId wanted = store(objects,
                GitObjectType.BLOB,
                "wanted\n".getBytes(StandardCharsets.UTF_8));
        ObjectId unknownHave = new ObjectId("f".repeat(40));

        Set<ObjectId> result = graph.reachableObjects(Set.of(wanted), false);
        result.removeAll(graph.reachableObjects(Set.of(unknownHave), true));

        assertThat(result).containsExactly(wanted);
    }

    @Test
    void identifiesEqualAndLinearCommitAncestry() throws Exception {
        ObjectId root = writeCommit(writeBlobTree("root.txt", "root"), null, "root");
        ObjectId child = writeCommit(writeBlobTree("child.txt", "child"), root, "child");

        assertThat(graph.isAncestor(root, root)).isTrue();
        assertThat(graph.isAncestor(root, child)).isTrue();
        assertThat(graph.isAncestor(child, root)).isFalse();
    }

    @Test
    void treatsIncompleteCommitHistoryAsUnrelated() throws Exception {
        ObjectId root = writeCommit(writeBlobTree("root.txt", "root"), null, "root");
        ObjectId missing = new ObjectId("f".repeat(40));

        assertThat(graph.isAncestor(missing, root)).isFalse();
        assertThat(graph.isAncestor(root, missing)).isFalse();
        assertThat(graph.hasCompleteClosure(missing)).isFalse();
    }

    @Test
    void rejectsMissingTreeButDoesNotRequireObjectsFromASubmodule() throws Exception {
        ObjectId missing = new ObjectId("f".repeat(40));
        ObjectId incomplete = writeCommit(missing, null, "incomplete");
        assertThat(graph.hasCompleteClosure(incomplete)).isFalse();
        assertThatThrownBy(() -> graph.reachableObjects(Set.of(incomplete), false))
                .isInstanceOf(java.io.FileNotFoundException.class);

        ObjectId tree = store(objects, GitObjectType.TREE, treeEntry("160000", "submodule", missing));
        ObjectId complete = writeCommit(tree, null, "submodule");
        assertThat(graph.hasCompleteClosure(complete)).isTrue();
        assertThat(graph.reachableObjects(Set.of(complete), false)).containsExactly(complete, tree);
    }

    @Test
    void followsBothMergeParentsButDoesNotTreatTreesOrBlobsAsAncestors() throws Exception {
        ObjectId tree = writeBlobTree("file", "content");
        ObjectId left = writeCommit(tree, null, "left");
        ObjectId right = writeCommit(tree, null, "right");
        ObjectId merge = writeCommitInternal(tree, List.of(left, right), "merge", 0);

        assertThat(graph.isAncestor(left, merge)).isTrue();
        assertThat(graph.isAncestor(right, merge)).isTrue();
        assertThat(graph.isAncestor(left, right)).isFalse();
        assertThat(graph.isAncestor(tree, merge)).isFalse();
    }

    @Test
    void malformedGraphDataIsAnErrorInsteadOfAnAbsentObject() throws Exception {
        ObjectId malformed = store(objects, GitObjectType.COMMIT,
                "not a commit\n\n".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> graph.hasCompleteClosure(malformed)).isInstanceOf(IOException.class);
    }

    private ObjectId writeCommit(ObjectId tree, ObjectId parent, String message) throws IOException {
        return writeCommitInternal(
                tree,
                parent == null ? List.of() : List.of(parent),
                message,
                0);
    }

    private ObjectId writeCommitInternal(
            ObjectId tree,
            List<ObjectId> parents,
            String message,
            long committerTimestamp) throws IOException {
        StringBuilder data = new StringBuilder("tree ").append(tree).append('\n');
        for (ObjectId parent : parents) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> ")
                .append(committerTimestamp)
                .append(" +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return store(objects, GitObjectType.COMMIT, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ObjectId writeBlobTree(String name, String content) throws IOException {
        ObjectId blob = store(objects,
                GitObjectType.BLOB,
                (content + "\n").getBytes(StandardCharsets.UTF_8));
        return store(objects,
                GitObjectType.TREE,
                treeEntry("100644", name, blob));
    }

    private ObjectId writeTag(ObjectId target, String name) throws IOException {
        return store(objects,
                GitObjectType.TAG,
                ("object " + target + "\n"
                        + "type commit\n"
                        + "tag " + name + "\n"
                        + "tagger Test <test@example.com> 0 +0000\n"
                        + "\nmessage\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(String mode, String name, ObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.toHex()));
        return output.toByteArray();
    }

}
