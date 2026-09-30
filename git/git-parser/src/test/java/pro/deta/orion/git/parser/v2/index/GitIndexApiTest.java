package pro.deta.orion.git.parser.v2.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitIndexApiTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingOneConnectionPreservesOthersAndAllowsReopening(boolean local) throws Exception {
        GitIndexApi factory = local ? new LocalGitIndex(directory) : new InMemoryIndex();
        IndexedObject object = object(PackId.create(), "a", 12);
        factory.withAccess(Optional.of(object.packId()), first -> {
            GitIndexAccess second = factory.createAccess(Optional.of(object.packId()));
            try {
                first.addObject(object);
                first.discard();
                assertThatThrownBy(first::snapshotRefs).isInstanceOf(IOException.class);
                assertThat(second.objects(object.packId())).containsExactly(object);
                second.publishIndex(pack(object.packId(), "b", 1));
            } finally {
                second.discard();
            }
            return null;
        });
        factory.withAccess(reopened -> {
            assertThat(reopened.locations(object.objectId())).containsExactly(object);
            return null;
        });
    }

    @Test
    void readingAndReopeningLeavesTheStoredIndexUnchanged() throws Exception {
        PackId id = PackId.create();
        IndexedObject object = object(id, "a", 12);
        try (LocalGitIndex factory = new LocalGitIndex(directory)) {
            factory.withAccess(Optional.of(id), access -> {
                access.addObject(object);
                return null;
            });
        }
        byte[] saved = Files.readAllBytes(directory.resolve("refs.mv"));
        try (LocalGitIndex factory = new LocalGitIndex(directory)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                factory.withAccess(access -> {
                    assertThat(access.findObject(id, object.objectId())).contains(object);
                    assertThat(access.locations(object.objectId())).isEmpty();
                    return null;
                });
                assertThat(Files.readAllBytes(directory.resolve("refs.mv"))).isEqualTo(saved);
            }
        }
    }

    @Test
    void buffersHiddenRowsAcrossHandlesAndFlushesOnPublicationAndLastClose() throws Exception {
        PackId id = PackId.create();
        PackMetadata metadata = pack(id, "b", 100);
        IndexedObject abandoned = object(PackId.create(), "c", 12);
        LocalGitIndex factory = new LocalGitIndex(directory);
        factory.withAccess(Optional.of(id), first -> {
            GitIndexAccess second = factory.createAccess(Optional.of(id));
            try {
                byte[] before = Files.readAllBytes(directory.resolve("refs.mv"));
                for (int i = 0; i < 100; i++) {
                    first.addObject(object(id, "a", 12 + i * 10));
                }
                assertThat(second.objects(id)).hasSize(100);
                assertThat(second.locations(new ObjectId("a".repeat(40)))).isEmpty();
                assertThat(Files.readAllBytes(directory.resolve("refs.mv"))).isEqualTo(before);
                first.discard();
                assertThatThrownBy(first::packs).isInstanceOf(IOException.class);
                second.publishIndex(metadata);
                assertThat(Files.readAllBytes(directory.resolve("refs.mv"))).isNotEqualTo(before);
                factory.withAccess(Optional.of(abandoned.packId()), writer -> {
                    writer.addObject(abandoned);
                    return null;
                });
            } finally {
                second.discard();
            }
            return null;
        });
        factory.withAccess(reopened -> {
            assertThat(reopened.findPack(id)).contains(metadata);
            assertThat(reopened.objects(id)).hasSize(100);
            assertThat(reopened.findObject(abandoned.packId(), abandoned.objectId())).contains(abandoned);
            assertThat(reopened.locations(abandoned.objectId())).isEmpty();
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifiedObjectsRemainPrivateUntilPublication(boolean local) throws Exception {
        {
            PackId id = PackId.create();
            GitIndexAccess index = index(local, id);
            try {
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
                assertThatThrownBy(() -> index.publishIndex(pack)).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> index.publishIndex(pack(id, "c", 1))).isInstanceOf(IOException.class);
                assertThat(index.packs()).isEmpty();
                index.addObject(base);
                assertThat(pending).containsExactly(delta);
                assertThat(index.objects(id)).containsExactly(base, delta);
                assertThat(index.publishIndex(pack)).isEqualTo(pack);
                assertThat(index.publishIndex(pack)).isEqualTo(pack);
                assertThat(index.findPack(id)).contains(pack);
                assertThat(index.packs(pack.packChecksum())).contains(pack);
                assertThat(index.locations(base.objectId())).containsExactly(base);
                assertThat(index.locations(delta.objectId())).containsExactly(delta);
                index.addObject(base);
                assertThatThrownBy(() -> index.addObject(object(id, "d", 50))).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> index.publishIndex(pack(id, "d", 2))).isInstanceOf(IOException.class);
                assertThat(index.packs()).containsExactly(pack);
            } finally {
                index.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void findsAllPublishedLocationsIncludingRepeatedObjectsInOnePack(boolean local) throws Exception {
        try (GitIndexApi factory = local ? new LocalGitIndex(directory) : new InMemoryIndex()) {
            IndexedObject first = object(PackId.create(), "a", 12);
            GitIndexAccess index = factory.createAccess(Optional.of(first.packId()));
            try {
                IndexedObject repeated = object(first.packId(), "a", 25);
                IndexedObject other = object(PackId.create(), "a", 12);
                index.addObject(first);
                index.addObject(first);
                assertThat(index.objects(first.packId())).containsExactly(first);
                assertThatThrownBy(() -> index.addObject(object(first.packId(), "b", 12)))
                        .isInstanceOf(IOException.class);
                index.addObject(repeated);
                index.publishIndex(pack(first.packId(), "b", 2));
                assertThat(index.locations(first.objectId())).containsExactlyInAnyOrder(first, repeated);
                GitIndexAccess second = factory.createAccess(Optional.of(other.packId()));
                try {
                    second.addObject(other);
                    second.publishIndex(pack(other.packId(), "c", 1));
                } finally {
                    second.discard();
                }
                assertThat(index.locations(first.objectId())).containsExactlyInAnyOrder(first, repeated, other);
                assertThat(index.findObject(first.packId(), first.objectId())).contains(first);
                assertThat(index.findObject(PackId.create(), first.objectId())).isEmpty();
            } finally {
                index.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentEqualChecksumsPublishBothPacksForTheClientToChoose(boolean local) throws Exception {
        GitIndexApi factory = local ? new LocalGitIndex(directory) : new InMemoryIndex();
        {
            IndexedObject a = object(PackId.create(), "a", 12);
            IndexedObject b = object(PackId.create(), "a", 12);
            GitIndexAccess first = factory.createAccess(Optional.of(a.packId()));
            try {
                GitIndexAccess second = factory.createAccess(Optional.of(b.packId()));
                try {
                    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        first.addObject(a);
                        second.addObject(b);
                        CyclicBarrier start = new CyclicBarrier(2);
                        Future<PackMetadata> one = executor.submit(() -> {
                            start.await(5, TimeUnit.SECONDS);
                            return first.publishIndex(pack(a.packId(), "b", 1));
                        });
                        Future<PackMetadata> two = executor.submit(() -> {
                            start.await(5, TimeUnit.SECONDS);
                            return second.publishIndex(pack(b.packId(), "b", 1));
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
                } finally {
                    second.discard();
                }
            } finally {
                first.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesAnEmptyPackAndRejectsInvalidObjectMetadata(boolean local) throws Exception {
        {
            PackMetadata empty = pack(PackId.create(), "a", 0);
            GitIndexAccess index = index(local, empty.packId());
            try {
                assertThat(index.objects(empty.packId())).isEmpty();
                assertThat(index.publishIndex(empty)).isEqualTo(empty);
                assertThat(index.packs()).containsExactly(empty);
                assertThatThrownBy(() -> new IndexedObject(empty.packId(), new ObjectId("a".repeat(40)),
                        GitObjectType.REF_DELTA, 4, 12, 10, Optional.empty()))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> new IndexedObject(empty.packId(), new ObjectId("a".repeat(40)),
                        GitObjectType.BLOB, 4, Long.MAX_VALUE, 10, Optional.empty()))
                        .isInstanceOf(IllegalArgumentException.class);
            } finally {
                index.discard();
            }
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
        {
            LocalGitIndex factory = new LocalGitIndex(directory);
            GitIndexAccess index = factory.createAccess(Optional.of(available.packId()));
            try {
                index.addObject(available);
                index.addObject(delta);
                factory.withAccess(Optional.of(abandoned.packId()), writer -> {
                    writer.addObject(abandoned);
                    return null;
                });
                index.publishIndex(pack);
            } finally {
                index.discard();
            }
        }
        {
            GitIndexAccess index = index(true);
            try {
                assertThat(index.packs()).containsExactly(pack);
                assertThat(index.packs(pack.packChecksum())).contains(pack);
                assertThat(index.locations(available.objectId())).containsExactly(available);
                assertThat(index.locations(delta.objectId())).containsExactly(delta);
                assertThat(index.objects(available.packId())).containsExactly(available, delta);
                assertThat(index.findObject(abandoned.packId(), abandoned.objectId())).contains(abandoned);
                assertThat(index.objects(abandoned.packId())).containsExactly(abandoned);
            } finally {
                index.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repositoryDefaultsToSha1AndRejectsMixedAlgorithms(boolean local) throws Exception {
        try (GitIndexApi factory = local ? new LocalGitIndex(directory) : new InMemoryIndex()) {
            PackId packId = PackId.create();
            GitIndexAccess index = factory.createAccess(Optional.of(packId));
            try {
                assertThat(factory.hashAlgorithm())
                        .isEqualTo(GitHashAlgorithm.SHA1);
                ObjectId sha256 = new ObjectId("a".repeat(64));
                assertThatThrownBy(() -> index.addObject(new IndexedObject(packId, sha256,
                        GitObjectType.BLOB, 3, 12, 8, Optional.empty())))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> index.addObject(new IndexedObject(packId, new ObjectId("a".repeat(40)),
                        GitObjectType.BLOB, 3, 12, 8, Optional.of(new IndexedObject.Delta(sha256, 2)))))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> index.publishIndex(new PackMetadata(packId,
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
            } finally {
                index.discard();
            }
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
        try (GitIndexApi factory = local ? new LocalGitIndex(directory, GitHashAlgorithm.SHA256)
                : new InMemoryIndex(GitHashAlgorithm.SHA256)) {
            GitIndexAccess index = factory.createAccess(java.util.Set.of(ref), Optional.of(id));
            try {
                assertThat(factory.hashAlgorithm()).isEqualTo(GitHashAlgorithm.SHA256);
                index.addObject(object);
                index.publishIndex(pack);
                assertThat(index.locations(objectId)).containsExactly(object);
                assertThat(index.packs(pack.packChecksum())).containsExactly(pack);
                assertThat(index.updateRefs(List.of(new RefUpdate(ref, Optional.empty(), Optional.of(objectId))), true))
                        .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
                index.updateHead(head);
                assertThat(index.snapshotRefs().head()).isEqualTo(head);
                assertThatThrownBy(() -> index.publishIndex(pack(PackId.create(), "a", 0)))
                        .isInstanceOf(IllegalArgumentException.class);
                index.apply();
            } finally {
                index.discard();
            }
        }
        if (local) {
            LocalGitIndex factory = new LocalGitIndex(directory);
            assertThat(factory.hashAlgorithm()).isEqualTo(GitHashAlgorithm.SHA256);
            factory.withAccess(reopened -> {
                assertThat(reopened.locations(objectId)).containsExactly(object);
                assertThat(reopened.packs(pack.packChecksum())).containsExactly(pack);
                assertThat(reopened.snapshotRefs().refs()).containsEntry(ref, objectId);
                assertThat(reopened.snapshotRefs().head()).isEqualTo(head);
                return null;
            });
            byte[] original = Files.readAllBytes(directory.resolve("refs.mv"));
            assertThatThrownBy(() -> new LocalGitIndex(directory, GitHashAlgorithm.SHA1).createAccess())
                    .isInstanceOf(IOException.class).hasMessageContaining("hash algorithm");
            assertThat(Files.readAllBytes(directory.resolve("refs.mv"))).isEqualTo(original);
        }
        assertThatThrownBy(() -> new PackMetadata(id, pack.packChecksum(), "pack", 0, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GitIndexAccess index(boolean local) throws IOException {
        return local ? new LocalGitIndex(directory).createAccess() : new InMemoryIndex().createAccess();
    }

    private GitIndexAccess index(boolean local, PackId id) throws IOException {
        return local ? new LocalGitIndex(directory).createAccess(Optional.of(id))
                : new InMemoryIndex().createAccess(Optional.of(id));
    }

    private static IndexedObject object(PackId packId, String hex, long offset) {
        return new IndexedObject(packId, new ObjectId(hex.repeat(40)), GitObjectType.BLOB,
                3, offset, 8, Optional.empty());
    }

    private static PackMetadata pack(PackId packId, String hex, long count) {
        return new PackMetadata(packId, new PackChecksum(hex.repeat(40)), packId.toString(), count, 64);
    }
}
