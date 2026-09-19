package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GitStorageApiTest {
    private static final ObjectId ID = new ObjectId("1".repeat(40));

    @Test
    void absentObjectsDoNotInvokeTheReader() throws Exception {
        var storage = new InMemoryGitStorage();
        assertThat(storage.api.exists(ID)).isFalse();
        assertThat(storage.api.readObject(ID, (type, size, baseId, input) -> {
            throw new AssertionError("An absent object must not invoke the reader");
        })).isEmpty();
        assertThat(storage.lookups).containsExactly(ID, ID);
    }

    @Test
    void readsRealContentThroughTheSameFacadeUsedForPresenceChecks() throws Exception {
        var storage = new InMemoryGitStorage();
        storage.put(ID, GitObjectType.COMMIT, Optional.empty(), new byte[]{10, 20, 30});
        assertThat(storage.api.exists(ID)).isTrue();
        var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> {
            assertThat(type).isEqualTo(GitObjectType.COMMIT);
            assertThat(size).isEqualTo(3);
            assertThat(baseId).isEmpty();
            return input.readBytes((int) size);
        });
        assertThat(storage.api.readObject(ID, reader)).hasValueSatisfying(
                bytes -> assertThat(bytes).containsExactly(10, 20, 30));
        assertThat(storage.lookups).containsExactly(ID, ID);
    }
}
