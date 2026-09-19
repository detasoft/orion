package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitStorageApiTest {
    @TempDir
    Path directory;
    private static final ObjectId ID = new ObjectId("1".repeat(40));

    @Test
    void absentObjectsDoNotInvokeTheReader() throws Exception {
        var storage = new GitStorageApi(directory);
        assertThat(storage.exists(ID)).isFalse();
        assertThat(storage.readObject(ID, (type, size, baseId, input) -> {
            throw new AssertionError("An absent object must not invoke the reader");
        })).isEmpty();
    }

    @Test
    void readsRealContentThroughTheSameFacadeUsedForPresenceChecks() throws Exception {
        var storage = new GitStorageApi(directory);
        ObjectId id = PackTestData.store(storage,
                GitObjectType.COMMIT, new byte[]{10, 20, 30});
        assertThat(storage.exists(id)).isTrue();
        var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> {
            assertThat(type).isEqualTo(GitObjectType.COMMIT);
            assertThat(size).isEqualTo(3);
            assertThat(baseId).isEmpty();
            return input.readBytes((int) size);
        });
        assertThat(storage.readObject(id, reader)).hasValueSatisfying(
                bytes -> assertThat(bytes).containsExactly(10, 20, 30));
    }
}
