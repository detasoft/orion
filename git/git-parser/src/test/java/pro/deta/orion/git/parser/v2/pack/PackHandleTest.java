package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;

import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackHandleTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsAndRewritesAcrossStorageBlocksAndTruncates(boolean memory) throws Exception {
        try (GitStorageAccess storage = memory ? new InMemoryStorage().createAccess() : new LocalGitStorage(directory).createAccess();
             PackHandle pack = storage.newPack(PackId.create())) {
            byte[] expected = new byte[20000];
            new Random(42).nextBytes(expected);
            ByteBuffer source = ByteBuffer.allocateDirect(expected.length).put(expected).flip();
            pack.write(pack.size(), source.asReadOnlyBuffer());
            ByteBuffer actual = ByteBuffer.allocateDirect(expected.length);
            assertThat(pack.read(0, actual)).isEqualTo(expected.length);
            assertThat(actual.flip()).isEqualTo(ByteBuffer.wrap(expected));
            pack.write(8191, ByteBuffer.wrap(new byte[]{7, 8, 9}));
            ByteBuffer boundary = ByteBuffer.allocate(3);
            assertThat(pack.read(8191, boundary)).isEqualTo(3);
            assertThat(boundary.array()).containsExactly(7, 8, 9);
            pack.truncate(8192);
            pack.write(pack.size(), ByteBuffer.wrap(new byte[]{10}));
            assertThat(pack.size()).isEqualTo(8193);
            ByteBuffer tail = ByteBuffer.allocate(2);
            assertThat(pack.read(8191, tail)).isEqualTo(2);
            assertThat(tail.array()).containsExactly(7, 10);
            assertThat(pack.read(pack.size(), ByteBuffer.allocate(1))).isEqualTo(-1);
            assertThatThrownBy(() -> pack.write(-1, ByteBuffer.allocate(0)))
                    .isInstanceOf(IllegalArgumentException.class);
            pack.flush();
        }
    }

    @Test
    void memoryStorageUsesLongOffsetsAndDoesNotRetainTruncatedBytes() throws Exception {
        try (GitStorageAccess storage = new InMemoryStorage().createAccess();
             PackHandle pack = storage.newPack(PackId.create())) {
            long offset = (long) Integer.MAX_VALUE + 100;
            pack.write(offset, ByteBuffer.wrap(new byte[]{1, 2, 3}));
            assertThat(pack.size()).isEqualTo(offset + 3);
            ByteBuffer data = ByteBuffer.allocate(4);
            assertThat(pack.read(offset - 1, data)).isEqualTo(4);
            assertThat(data.array()).containsExactly(0, 1, 2, 3);
            pack.truncate(offset);
            pack.write(offset + 2, ByteBuffer.wrap(new byte[]{9}));
            data.clear();
            assertThat(pack.read(offset - 1, data)).isEqualTo(4);
            assertThat(data.array()).containsExactly(0, 0, 0, 9);
            assertThat(pack.size()).isEqualTo(offset + 3);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                assertThat(files.iterator().hasNext()).isFalse();
            }
        }
    }

}
