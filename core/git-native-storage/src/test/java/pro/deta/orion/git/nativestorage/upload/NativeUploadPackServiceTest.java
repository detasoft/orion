package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitUploadStats;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeUploadPackServiceTest {
    private static final String NULL_ID = "0".repeat(40);

    private final LooseRefStore refs = new LooseRefStore();
    private final LooseObjectStore objects = new LooseObjectStore();

    @Test
    void writesPackfileSidebandAndReturnsStats() throws Exception {
        GitObjectId commit = writeSingleFileCommit();
        refs.update("refs/heads/main", NULL_ID, commit.value());
        NativeUploadPackService service = service();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        GitUploadStats stats = service.writeFetch(
                new NativeFetchRequest(Set.of(commit), Set.of(), true, true, true),
                output);

        byte[] response = output.toByteArray();
        assertThat(new String(response, 0, 13, StandardCharsets.US_ASCII)).isEqualTo("000dpackfile\n");
        assertThat(indexOf(response, new byte[]{1, 'P', 'A', 'C', 'K'})).isGreaterThan(0);
        assertThat(stats.totalObjects()).isEqualTo(3);
        assertThat(stats.reusedObjects()).isZero();
        assertThat(stats.packBytes()).isGreaterThan(32);
    }

    @Test
    void checksAccessBeforeObjectTraversal() {
        GitObjectId missing = GitObjectId.of("a".repeat(40));
        NativeUploadPackService service = new NativeUploadPackService(
                UnpooledByteBufAllocator.DEFAULT,
                "project",
                refs,
                objects,
                Optional.of("refs/heads/main"),
                _request -> {
                    throw new SecurityException("denied");
                });

        assertThatThrownBy(() -> service.writeFetch(
                new NativeFetchRequest(Set.of(missing), Set.of(), true, false, false),
                new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(GitUploadPackException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(GitUploadPackException.Kind.ACCESS_DENIED);
                    assertThat(failure).hasMessage("ACCESS_DENIED");
                });
    }

    private NativeUploadPackService service() {
        return new NativeUploadPackService(
                UnpooledByteBufAllocator.DEFAULT,
                "project",
                refs,
                objects,
                Optional.of("refs/heads/main"),
                _request -> {
                });
    }

    private GitObjectId writeSingleFileCommit() {
        GitObjectId blob = objects.write(ObjectType.BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId tree = objects.write(ObjectType.TREE, tree("README.md", blob));
        String commit = """
                tree %s
                author Native Test <native@example.test> 0 +0000
                committer Native Test <native@example.test> 0 +0000

                initial
                """.formatted(tree.value());
        return objects.write(ObjectType.COMMIT, commit.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] tree(String name, GitObjectId blob) {
        byte[] prefix = ("100644 " + name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] id = HexFormat.of().parseHex(blob.value());
        byte[] result = new byte[prefix.length + id.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(id, 0, result, prefix.length, id.length);
        return result;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return i;
            }
        }
        return -1;
    }
}
