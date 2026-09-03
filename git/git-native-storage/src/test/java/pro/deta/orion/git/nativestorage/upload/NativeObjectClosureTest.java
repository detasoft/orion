package pro.deta.orion.git.nativestorage.upload;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeObjectClosureTest {
    private final LooseObjectStore objects = new LooseObjectStore();
    private final NativeObjectClosure closure = new NativeObjectClosure(objects);

    @Test
    void traversesCommitTreeAndBlob() {
        GitObjectId blob = objects.write(ObjectType.BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId tree = objects.write(ObjectType.TREE, treeEntry("100644", "hello.txt", blob));
        GitObjectId commit = objects.write(
                ObjectType.COMMIT,
                ("tree " + tree + "\n"
                        + "author Test <test@example.com> 0 +0000\n"
                        + "committer Test <test@example.com> 0 +0000\n"
                        + "\ninitial\n").getBytes(StandardCharsets.UTF_8));

        Set<GitObjectId> result =
                closure.objectIdsFor(Set.of(commit), Set.of());

        assertThat(result)
                .containsExactlyInAnyOrder(commit, tree, blob);
    }

    @Test
    void excludesObjectsReachableFromHaves() {
        GitObjectId baseBlob = objects.write(ObjectType.BLOB, "base\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId baseTree = objects.write(ObjectType.TREE, treeEntry("100644", "file.txt", baseBlob));
        GitObjectId baseCommit = writeCommit(baseTree, null, "base");

        GitObjectId tipBlob = objects.write(ObjectType.BLOB, "tip\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId tipTree = objects.write(ObjectType.TREE, treeEntry("100644", "file.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(tipTree, baseCommit, "tip");

        Set<GitObjectId> result =
                closure.objectIdsFor(
                        Set.of(tipCommit),
                        Set.of(baseCommit));

        assertThat(result)
                .containsExactlyInAnyOrder(tipCommit, tipTree, tipBlob);
    }

    @Test
    void rejectsMissingWantedObjectWithTypedFailure() {
        GitObjectId missing = GitObjectId.of("f".repeat(40));

        assertThatThrownBy(() ->
                closure.objectIdsFor(Set.of(missing), Set.of()))
                .isInstanceOfSatisfying(GitUploadPackException.class, error -> {
                    assertThat(error.kind()).isEqualTo(GitUploadPackException.Kind.MISSING_OBJECT);
                    assertThat(error.getMessage()).isEqualTo("Requested Git object is unavailable");
                });
    }

    @Test
    void followsAnnotatedTagTargets() {
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "tagged\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId tree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "tagged.txt", blob));
        GitObjectId commit = writeCommit(tree, null, "tagged");
        GitObjectId tag = writeTag(commit, "v1");

        Set<GitObjectId> result =
                closure.objectIdsFor(Set.of(tag), Set.of());

        assertThat(result)
                .containsExactlyInAnyOrder(tag, commit, tree, blob);
    }

    @Test
    void blobNoneOmitsTreeReachedBlobsButKeepsTrees() {
        GitObjectId rootBlob = objects.write(
                ObjectType.BLOB,
                "root\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId nestedBlob = objects.write(
                ObjectType.BLOB,
                "nested\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId nestedTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "nested.txt", nestedBlob));
        GitObjectId rootTree = objects.write(
                ObjectType.TREE,
                treeEntries(
                        treeEntry("40000", "dir", nestedTree),
                        treeEntry("100644", "root.txt", rootBlob)));
        GitObjectId commit = writeCommit(rootTree, null, "filtered");

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(
                        Set.of(commit),
                        Set.of(),
                        0,
                        NativeObjectFilter.BLOB_NONE);

        assertThat(result.objectIds())
                .containsExactlyInAnyOrder(commit, rootTree, nestedTree);
        assertThat(result.objectIds()).doesNotContain(rootBlob, nestedBlob);
    }

    @Test
    void blobNoneKeepsExplicitBlobWants() {
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "direct\n".getBytes(StandardCharsets.UTF_8));

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(
                        Set.of(blob),
                        Set.of(),
                        0,
                        NativeObjectFilter.BLOB_NONE);

        assertThat(result.objectIds()).containsExactly(blob);
    }

    @Test
    void ignoresUnknownHaveRoots() {
        GitObjectId wanted = objects.write(
                ObjectType.BLOB,
                "wanted\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId unknownHave = GitObjectId.of("f".repeat(40));

        Set<GitObjectId> result = closure.objectIdsFor(
                Set.of(wanted),
                Set.of(unknownHave));

        assertThat(result).containsExactly(wanted);
    }

    @Test
    void selectsDepthOneTipClosureAndReportsTipAsShallowBoundary() {
        GitObjectId baseBlob = objects.write(
                ObjectType.BLOB,
                "base\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId baseTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", baseBlob));
        GitObjectId baseCommit = writeCommit(baseTree, null, "base");
        GitObjectId tipBlob = objects.write(
                ObjectType.BLOB,
                "tip\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId tipTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(tipTree, baseCommit, "tip");

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(Set.of(tipCommit), Set.of(), 1);

        assertThat(result.objectIds())
                .containsExactlyInAnyOrder(tipCommit, tipTree, tipBlob);
        assertThat(result.shallowBoundaries()).containsExactly(tipCommit);
    }

    @Test
    void selectsMergeParentsAtDepthTwoAndReportsOnlyTruncatedParents() {
        GitObjectId rootBlob = objects.write(
                ObjectType.BLOB,
                "root\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId rootTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "root.txt", rootBlob));
        GitObjectId rootCommit = writeCommit(rootTree, null, "root");
        GitObjectId leftTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "left.txt", rootBlob));
        GitObjectId leftCommit = writeCommit(leftTree, rootCommit, "left");
        GitObjectId rightTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "right.txt", rootBlob));
        GitObjectId rightCommit = writeCommit(rightTree, null, "right");
        GitObjectId mergeTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "merge.txt", rootBlob));
        GitObjectId mergeCommit = writeCommitWithParents(
                mergeTree,
                List.of(leftCommit, rightCommit),
                "merge");

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(Set.of(mergeCommit), Set.of(), 2);

        assertThat(result.objectIds())
                .contains(mergeCommit, leftCommit, rightCommit);
        assertThat(result.objectIds()).doesNotContain(rootCommit);
        assertThat(result.shallowBoundaries()).containsExactly(leftCommit);
    }

    @Test
    void deepenSinceStopsAtOlderCommitTimestamp() {
        GitObjectId rootTree = writeBlobTree("root.txt", "root");
        GitObjectId rootCommit = writeCommit(rootTree, null, "root", 100);
        GitObjectId middleTree = writeBlobTree("middle.txt", "middle");
        GitObjectId middleCommit = writeCommit(middleTree, rootCommit, "middle", 201);
        GitObjectId tipTree = writeBlobTree("tip.txt", "tip");
        GitObjectId tipCommit = writeCommit(tipTree, middleCommit, "tip", 300);

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(
                        Set.of(tipCommit),
                        Set.of(),
                        0,
                        Set.of(),
                        false,
                        200,
                        Set.of(),
                        NativeObjectFilter.NONE);

        assertThat(result.objectIds()).contains(tipCommit, middleCommit);
        assertThat(result.objectIds()).doesNotContain(rootCommit);
        assertThat(result.shallowBoundaries()).containsExactly(middleCommit);
    }

    @Test
    void deepenNotExcludesReachableHistoryAndReportsBranchBoundary() {
        GitObjectId rootTree = writeBlobTree("root.txt", "root");
        GitObjectId oldRootTree = writeBlobTree("old-root.txt", "old-root");
        GitObjectId oldRootCommit = writeCommit(oldRootTree, null, "old-root");
        GitObjectId rootCommit = writeCommit(rootTree, oldRootCommit, "root");
        GitObjectId leftTree = writeBlobTree("left.txt", "left");
        GitObjectId leftCommit = writeCommit(leftTree, rootCommit, "left");
        GitObjectId rightTree = writeBlobTree("right.txt", "right");
        GitObjectId rightCommit = writeCommit(rightTree, rootCommit, "right");
        GitObjectId mergeTree = writeBlobTree("merge.txt", "merge");
        GitObjectId mergeCommit = writeCommitWithParents(
                mergeTree,
                List.of(leftCommit, rightCommit),
                "merge");

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(
                        Set.of(mergeCommit),
                        Set.of(),
                        0,
                        Set.of(),
                        false,
                        -1,
                        Set.of(leftCommit),
                        NativeObjectFilter.NONE);

        assertThat(result.objectIds()).contains(mergeCommit, rightCommit);
        assertThat(result.objectIds())
                .doesNotContain(leftCommit, rootCommit, oldRootCommit);
        assertThat(result.shallowBoundaries())
                .containsExactlyInAnyOrder(mergeCommit, rightCommit);
    }

    @Test
    void deepenRelativeExtendsDepthFromClientShallowCommit() {
        GitObjectId rootTree = writeBlobTree("root.txt", "root");
        GitObjectId oldRootTree = writeBlobTree("old-root.txt", "old-root");
        GitObjectId oldRootCommit = writeCommit(oldRootTree, null, "old-root");
        GitObjectId rootCommit = writeCommit(rootTree, oldRootCommit, "root");
        GitObjectId baseTree = writeBlobTree("base.txt", "base");
        GitObjectId baseCommit = writeCommit(baseTree, rootCommit, "base");
        GitObjectId middleTree = writeBlobTree("middle.txt", "middle");
        GitObjectId middleCommit = writeCommit(middleTree, baseCommit, "middle");
        GitObjectId tipTree = writeBlobTree("tip.txt", "tip");
        GitObjectId tipCommit = writeCommit(tipTree, middleCommit, "tip");

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(
                        Set.of(tipCommit),
                        Set.of(),
                        2,
                        Set.of(baseCommit),
                        true,
                        -1,
                        Set.of(),
                        NativeObjectFilter.NONE);

        assertThat(result.objectIds())
                .contains(tipCommit, middleCommit, baseCommit, rootCommit);
        assertThat(result.shallowBoundaries()).containsExactly(rootCommit);
    }

    @Test
    void reportsClientShallowCommitAsUnshallowWhenParentsBecomeIncluded() {
        GitObjectId rootTree = writeBlobTree("root.txt", "root");
        GitObjectId rootCommit = writeCommit(rootTree, null, "root");
        GitObjectId baseTree = writeBlobTree("base.txt", "base");
        GitObjectId baseCommit = writeCommit(baseTree, rootCommit, "base");
        GitObjectId tipTree = writeBlobTree("tip.txt", "tip");
        GitObjectId tipCommit = writeCommit(tipTree, baseCommit, "tip");

        NativeObjectClosure.FetchSelection result =
                closure.selectionFor(
                        Set.of(tipCommit),
                        Set.of(),
                        3,
                        Set.of(baseCommit),
                        false,
                        -1,
                        Set.of(),
                        NativeObjectFilter.NONE);

        assertThat(result.objectIds())
                .contains(tipCommit, baseCommit, rootCommit);
        assertThat(result.shallowBoundaries()).isEmpty();
        assertThat(result.unshallowBoundaries()).containsExactly(baseCommit);
    }

    @Test
    void identifiesEqualAndLinearCommitAncestry() {
        GitObjectId root = writeCommit(writeBlobTree("root.txt", "root"), null, "root");
        GitObjectId child = writeCommit(writeBlobTree("child.txt", "child"), root, "child");

        assertThat(closure.isAncestor(root, root)).isTrue();
        assertThat(closure.isAncestor(root, child)).isTrue();
        assertThat(closure.isAncestor(child, root)).isFalse();
    }

    @Test
    void findsNearestMergeBaseForDivergedTips() {
        GitObjectId root = writeCommit(writeBlobTree("root.txt", "root"), null, "root");
        GitObjectId left = writeCommit(writeBlobTree("left.txt", "left"), root, "left");
        GitObjectId right = writeCommit(writeBlobTree("right.txt", "right"), root, "right");

        assertThat(closure.mergeBase(left, right)).contains(root);
        assertThat(closure.mergeBase(left, root)).contains(root);
    }

    @Test
    void hasNoMergeBaseForUnrelatedHistories() {
        GitObjectId left = writeCommit(writeBlobTree("left.txt", "left"), null, "left");
        GitObjectId right = writeCommit(writeBlobTree("right.txt", "right"), null, "right");

        assertThat(closure.mergeBase(left, right)).isEmpty();
    }

    @Test
    void treatsIncompleteCommitHistoryAsUnrelated() {
        GitObjectId root = writeCommit(writeBlobTree("root.txt", "root"), null, "root");
        GitObjectId missing = GitObjectId.of("f".repeat(40));

        assertThat(closure.isAncestor(missing, root)).isFalse();
        assertThat(closure.isAncestor(root, missing)).isFalse();
        assertThat(closure.mergeBase(root, missing)).isEmpty();
    }

    private GitObjectId writeCommit(GitObjectId tree, GitObjectId parent, String message) {
        return writeCommitInternal(
                tree,
                parent == null ? List.of() : List.of(parent),
                message,
                0);
    }

    private GitObjectId writeCommit(
            GitObjectId tree,
            GitObjectId parent,
            String message,
            long committerTimestamp) {
        return writeCommitInternal(
                tree,
                parent == null ? List.of() : List.of(parent),
                message,
                committerTimestamp);
    }

    private GitObjectId writeCommitWithParents(
            GitObjectId tree,
            List<GitObjectId> parents,
            String message) {
        return writeCommitInternal(
                tree,
                parents,
                message,
                0);
    }

    private GitObjectId writeCommitInternal(
            GitObjectId tree,
            List<GitObjectId> parents,
            String message,
            long committerTimestamp) {
        StringBuilder data = new StringBuilder("tree ").append(tree).append('\n');
        for (GitObjectId parent : parents) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> ")
                .append(committerTimestamp)
                .append(" +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return objects.write(ObjectType.COMMIT, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private GitObjectId writeBlobTree(String name, String content) {
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                (content + "\n").getBytes(StandardCharsets.UTF_8));
        return objects.write(
                ObjectType.TREE,
                treeEntry("100644", name, blob));
    }

    private GitObjectId writeTag(GitObjectId target, String name) {
        return objects.write(
                ObjectType.TAG,
                ("object " + target + "\n"
                        + "type commit\n"
                        + "tag " + name + "\n"
                        + "tagger Test <test@example.com> 0 +0000\n"
                        + "\nmessage\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(String mode, String name, GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }

    private static byte[] treeEntries(byte[]... entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            output.writeBytes(entry);
        }
        return output.toByteArray();
    }
}
