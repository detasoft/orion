package pro.deta.orion.git.nativestorage.object;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
}
