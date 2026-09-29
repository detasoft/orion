package pro.deta.orion.git.parser.v2.index;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitIndexApiTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifiedObjectsRemainPrivateUntilPublication(boolean local) throws Exception {
        try (GitIndexApi index = index(local)) {
            PackId id = PackId.create();
            IndexedObject base = object(id, "a", 12);
            IndexedObject delta = new IndexedObject(id, new ObjectId("b".repeat(40)), GitObjectType.BLOB,
                    20, 30, 8, Optional.of(new IndexedObject.Delta(base.objectId(), 5)));
            PackMetadata pack = pack(id, "c", 2);
            index.addObject(delta);
            List<IndexedObject> pending = index.objects(id);
            assertThat(index.findObject(id, delta.objectId())).contains(delta);
            assertThat(index.locations(delta.objectId())).isEmpty();
            assertThat(index.findPack(id)).isEmpty();
            assertThat(index.packs(pack.packChecksum())).isEmpty();
            assertThat(index.packs()).isEmpty();
            assertThatThrownBy(() -> index.publishPack(pack)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.publishPack(pack(id, "c", 1))).isInstanceOf(IOException.class);
            assertThat(index.packs()).isEmpty();
            index.addObject(base);
            assertThat(pending).containsExactly(delta);
            assertThat(index.objects(id)).containsExactly(base, delta);
            assertThat(index.publishPack(pack)).isEqualTo(pack);
            assertThat(index.publishPack(pack)).isEqualTo(pack);
            assertThat(index.findPack(id)).contains(pack);
            assertThat(index.packs(pack.packChecksum())).contains(pack);
            assertThat(index.locations(base.objectId())).containsExactly(base);
            assertThat(index.locations(delta.objectId())).containsExactly(delta);
            index.addObject(base);
            assertThatThrownBy(() -> index.addObject(object(id, "d", 50))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.publishPack(pack(id, "d", 2))).isInstanceOf(IOException.class);
            assertThat(index.packs()).containsExactly(pack);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void findsAllPublishedLocationsIncludingRepeatedObjectsInOnePack(boolean local) throws Exception {
        try (GitIndexApi index = index(local)) {
            IndexedObject first = object(PackId.create(), "a", 12);
            IndexedObject repeated = object(first.packId(), "a", 25);
            IndexedObject other = object(PackId.create(), "a", 12);
            index.addObject(first);
            index.addObject(first);
            assertThat(index.objects(first.packId())).containsExactly(first);
            assertThatThrownBy(() -> index.addObject(object(first.packId(), "b", 12)))
                    .isInstanceOf(IOException.class);
            index.addObject(repeated);
            index.addObject(other);
            index.publishPack(pack(first.packId(), "b", 2));
            assertThat(index.locations(first.objectId())).containsExactlyInAnyOrder(first, repeated);
            index.publishPack(pack(other.packId(), "c", 1));
            assertThat(index.locations(first.objectId())).containsExactlyInAnyOrder(first, repeated, other);
            assertThat(index.findObject(first.packId(), first.objectId())).contains(first);
            assertThat(index.findObject(PackId.create(), first.objectId())).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentEqualChecksumsPublishBothPacksForTheClientToChoose(boolean local) throws Exception {
        try (GitIndexApi first = index(local);
             GitIndexApi second = local ? index(true) : first;
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IndexedObject a = object(PackId.create(), "a", 12);
            IndexedObject b = object(PackId.create(), "a", 12);
            first.addObject(a);
            second.addObject(b);
            CyclicBarrier start = new CyclicBarrier(2);
            Future<PackMetadata> one = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return first.publishPack(pack(a.packId(), "b", 1));
            });
            Future<PackMetadata> two = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return second.publishPack(pack(b.packId(), "b", 1));
            });
            PackMetadata left = one.get(10, TimeUnit.SECONDS);
            PackMetadata right = two.get(10, TimeUnit.SECONDS);
            assertThat(left.packId()).isEqualTo(a.packId());
            assertThat(right.packId()).isEqualTo(b.packId());
            assertThat(first.packs()).containsExactlyInAnyOrder(left, right);
            assertThat(first.packs(left.packChecksum())).containsExactlyInAnyOrder(left, right);
            assertThat(second.locations(a.objectId())).containsExactlyInAnyOrder(a, b);
            assertThat(first.objects(a.packId())).containsExactly(a);
            assertThat(second.objects(b.packId())).containsExactly(b);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesAnEmptyPackAndRejectsInvalidObjectMetadata(boolean local) throws Exception {
        try (GitIndexApi index = index(local)) {
            PackMetadata empty = pack(PackId.create(), "a", 0);
            assertThat(index.objects(empty.packId())).isEmpty();
            assertThat(index.publishPack(empty)).isEqualTo(empty);
            assertThat(index.packs()).containsExactly(empty);
            assertThatThrownBy(() -> new IndexedObject(empty.packId(), new ObjectId("a".repeat(40)),
                    GitObjectType.REF_DELTA, 4, 12, 10, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IndexedObject(empty.packId(), new ObjectId("a".repeat(40)),
                    GitObjectType.BLOB, 4, Long.MAX_VALUE, 10, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reopensAvailableAndPendingObjectsWithoutPublishingAbandonedPacks() throws Exception {
        IndexedObject available = object(PackId.create(), "a", 12);
        IndexedObject abandoned = object(PackId.create(), "a", 25);
        IndexedObject delta = new IndexedObject(available.packId(), new ObjectId("c".repeat(40)),
                GitObjectType.BLOB, 5, 40, 9, Optional.of(new IndexedObject.Delta(available.objectId(), 3)));
        PackMetadata pack = new PackMetadata(available.packId(), new PackChecksum("b".repeat(40)),
                "packs/каталог:pack\tdata", 2, 64);
        try (GitIndexApi index = index(true)) {
            index.addObject(available);
            index.addObject(delta);
            index.addObject(abandoned);
            index.publishPack(pack);
        }
        try (GitIndexApi index = index(true)) {
            assertThat(index.packs()).containsExactly(pack);
            assertThat(index.packs(pack.packChecksum())).contains(pack);
            assertThat(index.locations(available.objectId())).containsExactly(available);
            assertThat(index.locations(delta.objectId())).containsExactly(delta);
            assertThat(index.objects(available.packId())).containsExactly(available, delta);
            assertThat(index.findObject(abandoned.packId(), abandoned.objectId())).contains(abandoned);
            assertThat(index.objects(abandoned.packId())).containsExactly(abandoned);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repositoryDefaultsToSha1AndRejectsMixedAlgorithms(boolean local) throws Exception {
        try (GitIndexApi index = index(local)) {
            assertThat(index.hashAlgorithm()).isEqualTo(GitHashAlgorithm.SHA1);
            PackId packId = PackId.create();
            ObjectId sha256 = new ObjectId("a".repeat(64));
            assertThatThrownBy(() -> index.addObject(new IndexedObject(packId, sha256,
                    GitObjectType.BLOB, 3, 12, 8, Optional.empty())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.addObject(new IndexedObject(packId, new ObjectId("a".repeat(40)),
                    GitObjectType.BLOB, 3, 12, 8, Optional.of(new IndexedObject.Delta(sha256, 2)))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.publishPack(new PackMetadata(packId,
                    new PackChecksum("b".repeat(64)), "pack", 0, 44)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.updateHead(new Head.Detached(new CommitId(sha256.toBytes()))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.updateRefs(List.of(new RefUpdate(new RefId("refs/heads/main"),
                    Optional.empty(), Optional.of(sha256))), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(index.objects(packId)).isEmpty();
            assertThat(index.packs()).isEmpty();
            assertThat(index.snapshotRefs().refs()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storesSha256ObjectsChecksumsAndRefs(boolean local) throws Exception {
        PackId id = PackId.create();
        ObjectId objectId = new ObjectId("a".repeat(64));
        IndexedObject object = new IndexedObject(id, objectId, GitObjectType.COMMIT, 3, 12, 8,
                Optional.empty());
        PackMetadata pack = new PackMetadata(id, new PackChecksum("b".repeat(64)), "pack", 1, 64);
        RefId ref = new RefId("refs/heads/main");
        Head head = new Head.Detached(new CommitId(objectId.toBytes()));
        try (GitIndexApi index = local ? new LocalGitIndex(directory, GitHashAlgorithm.SHA256)
                : new InMemoryIndex(GitHashAlgorithm.SHA256)) {
            assertThat(index.hashAlgorithm()).isEqualTo(GitHashAlgorithm.SHA256);
            index.addObject(object);
            index.publishPack(pack);
            assertThat(index.locations(objectId)).containsExactly(object);
            assertThat(index.packs(pack.packChecksum())).containsExactly(pack);
            assertThat(index.updateRefs(List.of(new RefUpdate(ref, Optional.empty(), Optional.of(objectId))), true))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            index.updateHead(head);
            assertThat(index.snapshotRefs().head()).isEqualTo(head);
            assertThatThrownBy(() -> index.publishPack(pack(PackId.create(), "a", 0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        if (local) {
            try (GitIndexApi reopened = new LocalGitIndex(directory)) {
                assertThat(reopened.hashAlgorithm()).isEqualTo(GitHashAlgorithm.SHA256);
                assertThat(reopened.locations(objectId)).containsExactly(object);
                assertThat(reopened.packs(pack.packChecksum())).containsExactly(pack);
                assertThat(reopened.snapshotRefs().refs()).containsEntry(ref, objectId);
                assertThat(reopened.snapshotRefs().head()).isEqualTo(head);
            }
            byte[] original = Files.readAllBytes(directory.resolve("refs.mv"));
            assertThatThrownBy(() -> new LocalGitIndex(directory, GitHashAlgorithm.SHA1))
                    .isInstanceOf(IOException.class).hasMessageContaining("hash algorithm");
            assertThat(Files.readAllBytes(directory.resolve("refs.mv"))).isEqualTo(original);
        }
        assertThatThrownBy(() -> new PackMetadata(id, pack.packChecksum(), "pack", 0, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPreviousFormatWithoutModifyingIt() throws Exception {
        Path file = directory.resolve("refs.mv");
        try (MVStore store = new MVStore.Builder().fileName(file.toString()).open()) {
            store.setStoreVersion(1);
            store.<String, String>openMap("refs").put("HEAD", "ref: refs/heads/main");
            store.commit();
        }
        byte[] original = Files.readAllBytes(file);
        assertThatThrownBy(() -> new LocalGitIndex(directory)).isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported repository index format");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    private GitIndexApi index(boolean local) throws IOException {
        return local ? new LocalGitIndex(directory) : new InMemoryIndex();
    }

    private static IndexedObject object(PackId packId, String hex, long offset) {
        return new IndexedObject(packId, new ObjectId(hex.repeat(40)), GitObjectType.BLOB,
                3, offset, 8, Optional.empty());
    }

    private static PackMetadata pack(PackId packId, String hex, long count) {
        return new PackMetadata(packId, new PackChecksum(hex.repeat(40)), packId.toString(), count, 64);
    }
}
