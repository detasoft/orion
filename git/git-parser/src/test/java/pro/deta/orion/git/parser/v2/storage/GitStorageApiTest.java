package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitStorageApiTest {
    @TempDir
    Path directory;
    private static final ObjectId ID = new ObjectId("1".repeat(40));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void locatesOrderedUniqueObjectsAcrossPacksAndReadsExactCompressedRanges(boolean disk) throws Exception {
        try (GitStorageApi storage = disk ? new GitStorageApi(directory) : new GitStorageApi()) {
            byte[] first = {1, 2, 3};
            byte[] second = {4, 5};
            byte[] third = {6};
            ObjectId firstId = PackTestData.objectId(GitObjectType.BLOB, first);
            ObjectId secondId = PackTestData.objectId(GitObjectType.BLOB, second);
            IndexedPack target = PackTestData.ingest(
                    PackTestData.pack(PackTestData.blob(first), PackTestData.blob(second)), storage.newPack());
            new GitPackObjectResolver(target, storage).complete();
            PackId pair = storage.persist(target);
            ObjectId thirdId = PackTestData.store(storage, GitObjectType.BLOB, third);
            List<PackObjectLocation> locations = storage.locateObjects(List.of(thirdId, secondId, firstId, thirdId, ID));
            assertThat(locations).extracting(PackObjectLocation::objectId)
                    .containsExactly(thirdId, secondId, firstId);
            assertThat(locations.getFirst().packId()).isNotEqualTo(pair);
            assertThat(locations.get(1).packId()).isEqualTo(pair);
            assertThat(locations.get(2).packId()).isEqualTo(pair);
            assertThat(locations.get(2).end()).isEqualTo(locations.get(1).entry().offset());
            assertThat(storage.packObjectIds(pair)).containsExactlyInAnyOrder(firstId, secondId);
            byte[][] contents = {third, second, first};
            for (int index = 0; index < locations.size(); index++) {
                PackObjectLocation location = locations.get(index);
                byte[] expected = contents[index];
                byte[] compressed = storage.readObject(location, (type, size, base, input) -> {
                    assertThat(type).isEqualTo(GitObjectType.BLOB);
                    assertThat(size).isEqualTo(expected.length);
                    assertThat(base).isEmpty();
                    return input.newInputStream().readAllBytes();
                });
                assertThat(compressed).isEqualTo(PackTestData.compressed(expected));
            }
        }
    }

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
