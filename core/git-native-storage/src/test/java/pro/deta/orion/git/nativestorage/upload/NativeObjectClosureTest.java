package pro.deta.orion.git.nativestorage.upload;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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

    private GitObjectId writeCommit(GitObjectId tree, GitObjectId parent, String message) {
        StringBuilder data = new StringBuilder("tree ").append(tree).append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> 0 +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return objects.write(ObjectType.COMMIT, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(String mode, String name, GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }
}
