package pro.deta.orion.git.nativestorage.object;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LooseObjectStoreTest {
    private final LooseObjectStore store = new LooseObjectStore();

    @Test
    void writeAndReadBlobObject() {
        byte[] data = "hello world".getBytes();
        GitObjectId id = store.write(ObjectType.BLOB, data);

        assertThat(id.value()).hasSize(40);
        Optional<LooseObject> obj = store.read(id);
        assertThat(obj).isPresent();
        assertThat(obj.get().type()).isEqualTo(ObjectType.BLOB);
        assertThat(obj.get().data()).isEqualTo(data);
    }

    @Test
    void writeAndReadCommitObject() {
        byte[] data = "tree 0000000000000000000000000000000000000000\nauthor A <a@b.com> 0 +0000\n\nTest\n".getBytes();
        GitObjectId id = store.write(ObjectType.COMMIT, data);

        Optional<LooseObject> obj = store.read(id);
        assertThat(obj).isPresent();
        assertThat(obj.get().type()).isEqualTo(ObjectType.COMMIT);
    }

    @Test
    void containsReturnsTrueForWrittenObject() {
        byte[] data = "test".getBytes();
        GitObjectId id = store.write(ObjectType.BLOB, data);

        assertThat(store.contains(id)).isTrue();
    }

    @Test
    void containsReturnsFalseForAbsentObject() {
        GitObjectId id = GitObjectId.of("a".repeat(40));

        assertThat(store.contains(id)).isFalse();
    }

    @Test
    void readReturnsEmptyForAbsentObject() {
        GitObjectId id = GitObjectId.of("b".repeat(40));

        assertThat(store.read(id)).isEmpty();
    }

    @Test
    void writeSameDataProducesSameId() {
        byte[] data = "deterministic".getBytes();
        GitObjectId id1 = store.write(ObjectType.BLOB, data);
        GitObjectId id2 = store.write(ObjectType.BLOB, data);

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void putAllCopiesObjectsFromOtherStore() {
        LooseObjectStore other = new LooseObjectStore();
        GitObjectId id = other.write(ObjectType.BLOB, "from other".getBytes());

        store.putAll(other);

        assertThat(store.contains(id)).isTrue();
    }

    @Test
    void writtenBlobIdMatchesGitObjectId() {
        byte[] data = "blob content\n".getBytes();
        GitObjectId id = store.write(ObjectType.BLOB, data);
        assertThat(id.value()).matches("[0-9a-f]{40}");
    }

    @Test
    void readsOnlyRequestedPrefixOfLargeTagObject() {
        byte[] objectLine = (
                "object 0123456789abcdef0123456789abcdef01234567\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[1024 * 1024 + objectLine.length];
        Arrays.fill(data, (byte) 'x');
        System.arraycopy(objectLine, 0, data, 0, objectLine.length);
        GitObjectId id = store.write(ObjectType.TAG, data);

        Optional<LooseObjectPrefix> prefix = store.readPrefix(id, 48);

        assertThat(prefix).isPresent();
        assertThat(prefix.get().id()).isEqualTo(id);
        assertThat(prefix.get().type()).isEqualTo(ObjectType.TAG);
        assertThat(prefix.get().declaredDataLength()).isEqualTo(data.length);
        assertThat(prefix.get().dataPrefix())
                .hasSize(48)
                .isEqualTo(objectLine);
    }

    @Test
    void rejectsNegativePrefixLength() {
        GitObjectId id = GitObjectId.of("c".repeat(40));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.readPrefix(id, -1));
    }

    @Test
    void persistsObjectsAcrossStoreInstances(@TempDir Path temporaryDirectory) {
        Path objectsDirectory = temporaryDirectory.resolve("objects");
        LooseObjectStore writer = new LooseObjectStore(objectsDirectory);
        byte[] data = "durable".getBytes(StandardCharsets.UTF_8);

        GitObjectId id = writer.write(ObjectType.BLOB, data);

        LooseObjectStore reader = new LooseObjectStore(objectsDirectory);
        assertThat(reader.read(id))
                .isPresent()
                .get()
                .satisfies(object -> {
                    assertThat(object.type()).isEqualTo(ObjectType.BLOB);
                    assertThat(object.data()).isEqualTo(data);
                });
        assertThat(Files.isRegularFile(objectsDirectory
                .resolve(id.value().substring(0, 2))
                .resolve(id.value().substring(2)))).isTrue();
    }
}
