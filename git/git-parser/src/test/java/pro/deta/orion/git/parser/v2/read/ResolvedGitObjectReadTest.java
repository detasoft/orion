package pro.deta.orion.git.parser.v2.read;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedGitObjectReadTest {
    private static final ObjectId BASE = new ObjectId("1111111111111111111111111111111111111111");
    private static final ObjectId DELTA = new ObjectId("2222222222222222222222222222222222222222");
    @TempDir
    Path directory;
    private GitStorageApi storage;

    @BeforeEach
    void setup() throws Exception {
        storage = new GitStorageApi(directory);
    }

    @Test
    void streamsFullContentWithoutLookingUpABase() throws Exception {
        var reader = new ResolvedGitObjectRead<>(storage, (type, size, baseId, content) -> {
            assertThat(type).isEqualTo(GitObjectType.COMMIT);
            assertThat(size).isEqualTo(3);
            assertThat(baseId).isEmpty();
            return content.readUnsignedByte();
        });
        assertThat(read(GitObjectType.COMMIT, Optional.empty(), new byte[]{42, 43, 44}, reader)).isEqualTo(42);
    }

    @Test
    void resolvesRefDeltaChainsAndPassesTheRestoredTypeAndSize() throws Exception {
        ObjectId deltaId = PackTestData.storeDelta(storage, GitObjectType.TREE, new byte[]{10, 20, 30},
                new byte[]{3, 4, (byte) 0x90, 3, 1, 40}, new byte[]{10, 20, 30, 40});
        var reader = new ResolvedGitObjectRead<>(storage, (type, size, baseId, content) -> {
            assertThat(type).isEqualTo(GitObjectType.TREE);
            assertThat(size).isEqualTo(3);
            assertThat(baseId).isEmpty();
            return content.readBytes((int) size);
        });
        byte[] delta = {4, 3, (byte) 0x91, 1, 2, 1, 50};
        assertThat(read(GitObjectType.REF_DELTA, Optional.of(deltaId), delta, reader))
                .containsExactly(20, 30, 50);
        assertThat(read(GitObjectType.REF_DELTA, Optional.of(deltaId), delta, reader))
                .containsExactly(20, 30, 50);
    }

    @Test
    void handlesDefaultCopyLengthAndEmptyResult() throws Exception {
        byte[] base = new byte[65536];
        base[65535] = 42;
        ObjectId storedBase = PackTestData.store(storage, GitObjectType.BLOB, base);
        var reader = bytesReader();
        assertThat(read(GitObjectType.REF_DELTA, Optional.of(storedBase),
                new byte[]{(byte) 0x80, (byte) 0x80, 4, (byte) 0x80, (byte) 0x80, 4, (byte) 0x80}, reader))
                .isEqualTo(base);
        assertThat(read(GitObjectType.REF_DELTA, Optional.of(storedBase),
                new byte[]{(byte) 0x80, (byte) 0x80, 4, 0}, reader)).isEmpty();
    }

    @Test
    void rejectsOfsDeltaExplicitly() throws Exception {
        assertThatThrownBy(() -> read(GitObjectType.OFS_DELTA, Optional.empty(), new byte[0], bytesReader()))
                .isInstanceOf(IllegalStateException.class).hasMessage("not yet supported");
    }

    @Test
    void rejectsMissingBaseMetadataAndAbsentBaseObjects() throws Exception {
        assertThatThrownBy(() -> read(GitObjectType.REF_DELTA, Optional.empty(), new byte[0], bytesReader()))
                .isInstanceOf(IOException.class).hasMessageContaining("no base ObjectId");
        assertThatThrownBy(() -> read(GitObjectType.REF_DELTA, Optional.of(BASE), new byte[0], bytesReader()))
                .isInstanceOf(IOException.class).hasMessageContaining("Missing delta base");
    }

    @Test
    void rejectsInvalidInstructionsEvenWhenConsumerDoesNotRead() throws Exception {
        ObjectId storedBase = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{42});
        byte[][] invalid = {
                {2, 0},                         // wrong base size
                {1, 1, 0},                      // reserved opcode
                {1, 1, 1},                      // truncated insert
                {1, 1, (byte) 0x91, 1, 1},      // copy outside base
                {1, 1, (byte) 0x90, 2},          // copy exceeds target
                {1, 1},                         // missing output
                {1, 0, 1, 42},                  // instructions after target end
                {1, 1, (byte) 0x91}              // truncated copy operands
        };
        for (byte[] delta : invalid) {
            var reader = new ResolvedGitObjectRead<>(storage, (type, size, baseId, content) -> Boolean.TRUE);
            assertThatThrownBy(() -> read(GitObjectType.REF_DELTA, Optional.of(storedBase), delta, reader))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void closesUnreturnedResourceWhenLateDeltaValidationFails() throws Exception {
        ObjectId storedBase = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{42});
        boolean[] closed = {false};
        var reader = new ResolvedGitObjectRead<>(storage, (type, size, baseId, content) -> {
            assertThat(content.readUnsignedByte()).isEqualTo(43);
            return (AutoCloseable) () -> {
                closed[0] = true;
                throw new IOException("cleanup");
            };
        });
        assertThatThrownBy(() -> read(GitObjectType.REF_DELTA, Optional.of(storedBase), new byte[]{1, 2, 1, 43, 0}, reader))
                .isInstanceOf(IOException.class).hasMessageContaining("zero delta")
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        assertThat(closed[0]).isTrue();
    }

    private ResolvedGitObjectRead<byte[]> bytesReader() {
        return new ResolvedGitObjectRead<>(storage,
                (type, size, baseId, content) -> content.readBytes(Math.toIntExact(size)));
    }

    private static <R> R read(GitObjectType type, Optional<ObjectId> baseId, byte[] payload,
                              GitObjectRead<R> reader) throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var output = new DeflaterOutputStream(compressed)) {
            output.write(payload);
        }
        try (var source = new BufferedByteInputV2(new ByteArrayInputStream(compressed.toByteArray()))) {
            return reader.read(type, payload.length, baseId, source);
        }
    }

}
