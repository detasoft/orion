package pro.deta.orion.git.parser.v2.storage.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.GitRepositoryContext.publishRefs;
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

class InMemoryStorageTest {
    @TempDir
    Path directory;
    private final InMemoryIndex indexApi = new InMemoryIndex();
    private static final RefId FIRST = new RefId("refs/heads/first");
    private static final RefId SECOND = new RefId("refs/heads/second");

    @Test
    void preservesRefResultOrderAndAtomicFailurePrecedence() throws Exception {
        {
            try (GitStorageAccess storage = new InMemoryStorage().createAccess()) {
                indexApi.withAccess(index -> {
                    ObjectId old = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{1});
                    ObjectId next = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{2});
                    ObjectId absent = new ObjectId("a".repeat(40));
                    assertThat(publishRefs(
                            storage, indexApi, List.of(update(FIRST, null, old)), true).getFirst().status())
                            .isEqualTo(APPLIED);
                    RefsSnapshot before = index.snapshotRefs();
                    List<RefUpdate> changes = List.of(update(SECOND, null, next), update(FIRST, next, old));
                    assertThat(publishRefs(
                            storage, indexApi, changes, true)).extracting(RefUpdateResult::status)
                            .containsExactly(ATOMIC_ABORTED, EXPECTED_OLD_MISMATCH);
                    assertThat(index.snapshotRefs()).isEqualTo(before);
                    assertThat(publishRefs(
                            storage, indexApi, List.of(update(SECOND, null, absent), update(FIRST, next, old)), true))
                            .extracting(RefUpdateResult::status).containsExactly(OBJECT_NOT_FOUND, ATOMIC_ABORTED);
                    assertThat(publishRefs(
                            storage, indexApi, changes, false)).extracting(RefUpdateResult::status)
                            .containsExactly(APPLIED, EXPECTED_OLD_MISMATCH);
                    assertThat(before.refs()).containsExactly(Map.entry(FIRST, old));
                    assertThat(index.snapshotRefs().refs()).containsEntry(SECOND, next);
                    assertThat(publishRefs(
                            storage, indexApi, List.of(update(SECOND, next, null)), false).getFirst().status())
                            .isEqualTo(APPLIED);
                    assertThat(index.snapshotRefs()).isEqualTo(before);
                    assertThatThrownBy(() -> publishRefs(
                            storage, indexApi, List.of(update(FIRST, old, next),
                            update(FIRST, old, next)), true)).isInstanceOf(IllegalArgumentException.class);
                    return null;
                });
            }
        }
    }

    @Test
    void admitsOnlyOneConcurrentExpectedOldWinner() throws Exception {
        {
            try (GitStorageAccess storage = new InMemoryStorage().createAccess()) {
                GitIndexAccess index = indexApi.createAccess();
                try {
                    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        ObjectId old = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{1});
                        ObjectId next = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{2});
                        publishRefs(storage, indexApi, List.of(update(FIRST, null, old)), true);
                        CyclicBarrier start = new CyclicBarrier(2);
                        List<Future<RefUpdateResult.Status>> attempts = new ArrayList<>();
                        for (int i = 0; i < 2; i++) {
                            attempts.add(executor.submit(() -> {
                                start.await(5, TimeUnit.SECONDS);
                                return publishRefs(
                                        storage, indexApi, List.of(update(FIRST, old, next)), true).getFirst().status();
                            }));
                        }
                        assertThat(List.of(attempts.get(0).get(5, TimeUnit.SECONDS), attempts.get(1).get(5, TimeUnit.SECONDS)))
                                .containsExactlyInAnyOrder(APPLIED, EXPECTED_OLD_MISMATCH);
                    }
                } finally {
                    index.discard();
                }
            }
        }
    }

    @Test
    void snapshotsNeverExposePartOfAnAtomicBatch() throws Exception {
        {
            try (GitStorageAccess storage = new InMemoryStorage().createAccess()) {
                GitIndexAccess index = indexApi.createAccess();
                try {
                    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        ObjectId first = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{1});
                        ObjectId second = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{2});
                        publishRefs(
                                storage, indexApi, List.of(update(FIRST, null, first), update(SECOND, null, first)), true);
                        CountDownLatch start = new CountDownLatch(1);
                        Future<?> writer = executor.submit(() -> {
                            start.await(5, TimeUnit.SECONDS);
                            for (int i = 0; i < 1000; i++) {
                                ObjectId old = i % 2 == 0 ? first : second;
                                ObjectId next = i % 2 == 0 ? second : first;
                                assertThat(publishRefs(
                                        storage, indexApi,
                                        List.of(update(FIRST, old, next), update(SECOND, old, next)), true))
                                        .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
                            }
                            return null;
                        });
                        Future<?> reader = executor.submit(() -> {
                            start.countDown();
                            for (int i = 0; i < 2000; i++) {
                                Map<RefId, ObjectId> refs = index.snapshotRefs().refs();
                                assertThat(refs.get(FIRST)).isEqualTo(refs.get(SECOND));
                            }
                            return null;
                        });
                        reader.get(10, TimeUnit.SECONDS);
                        writer.get(10, TimeUnit.SECONDS);
                    }
                } finally {
                    index.discard();
                }
            }
        }
    }

    @Test
    void callbacksReleaseRepositoryLockAndFailuresPreservePublishedPacks() throws Exception {
        {
            try (GitStorageAccess storage = new InMemoryStorage().createAccess()) {
                GitIndexAccess index = indexApi.createAccess();
                try {
                    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        ObjectId object = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{1});
                        PackMetadata published = index.packs().getFirst();
                        assertThat(GitObjectRead.read(storage, index, object, (type, size, base, input) -> {
                            try {
                                return executor.submit(() -> publishRefs(
                                        storage, indexApi, List.of(update(FIRST, null, object)), true))
                                        .get(5, TimeUnit.SECONDS).getFirst().status();
                            } catch (Exception failure) {
                                throw new IOException(failure);
                            }
                        })).contains(APPLIED);
                        assertThatThrownBy(() -> indexApi.withAccess(Optional.of(PackId.create()), writer ->
                                PackTestData.ingest(new byte[0], storage, writer))).isInstanceOf(IOException.class);
                        assertThat(index.packs()).containsExactly(published);
                        assertThat(GitObjectRead.exists(storage, index, object)).isTrue();
                    }
                } finally {
                    index.discard();
                }
            }
        }
    }

    @Test
    void isolatesRepositoriesAndConsumesPublicationAfterClose() throws Exception {
        GitStorageAccess first = new InMemoryStorage().createAccess();
        InMemoryIndex firstOwner = new InMemoryIndex();
        firstOwner.withAccess(firstIndex -> {
            {
                try (first) {
                    try {
                        try (GitStorageAccess second = new InMemoryStorage().createAccess()) {
                            GitIndexAccess secondIndex = new InMemoryIndex().createAccess();
                            try {
                                ObjectId object = PackTestData.store(first, firstOwner, GitObjectType.BLOB,
                                        new byte[]{1});
                                Head detached = new Head.Detached(new CommitId(object.toBytes()));
                                firstIndex.updateHead(detached);
                                assertThat(firstIndex.snapshotRefs().head()).isEqualTo(detached);
                                assertThat(secondIndex.snapshotRefs().head())
                                        .isEqualTo(new Head.Symbolic(new RefId("refs/heads/main")));
                                assertThat(GitObjectRead.exists(second, secondIndex, object)).isFalse();
                                assertThat(secondIndex.packs()).isEmpty();
                                assertThatThrownBy(() -> firstIndex.updateHead(new Head.Symbolic(new RefId("HEAD"))))
                                        .isInstanceOf(IllegalArgumentException.class);
                                PackMetadata attempt = firstOwner.withAccess(Optional.of(PackId.create()), writer ->
                                        PackTestData.ingest(PackTestData.pack(PackTestData.blob(new byte[]{2})),
                                                first, writer));
                                first.close();
                                assertThatThrownBy(() -> first.exists(attempt.packId())).isInstanceOf(ClosedChannelException.class);
                                assertThat(firstIndex.snapshotRefs().head()).isEqualTo(detached);
                                firstIndex.discard();
                                assertThatThrownBy(firstIndex::snapshotRefs).isInstanceOf(ClosedChannelException.class);
                                assertThatThrownBy(() -> first.newPack(PackId.create())).isInstanceOf(ClosedChannelException.class);
                                assertThat(secondIndex.packs()).isEmpty();
                            } finally {
                                secondIndex.discard();
                            }
                        }
                    } finally {
                        firstIndex.discard();
                    }
                }
            }
            return null;
        });
    }

    @Test
    void closingIndexLeavesStoredObjectsReadable() throws Exception {
        {
            try (GitStorageAccess storage = new InMemoryStorage().createAccess()) {
                indexApi.withAccess(index -> {
                    ObjectId object = PackTestData.store(storage, indexApi, GitObjectType.BLOB, new byte[]{1});
                    assertThat(publishRefs(
                            storage, indexApi, List.of(update(FIRST, null, object)), true).getFirst().status())
                            .isEqualTo(APPLIED);
                    IndexedObject location = index.locations(object).getFirst();
                    index.discard();
                    assertThatThrownBy(index::snapshotRefs).isInstanceOf(ClosedChannelException.class);
                    assertThat(storage.exists(location.packId())).isTrue();
                    assertThat(GitObjectRead.<GitObjectType>read(storage, location, (type, size, base, input) -> type))
                            .isEqualTo(GitObjectType.BLOB);
                    return null;
                });
            }
        }
    }

    private static RefUpdate update(RefId ref, ObjectId old, ObjectId next) {
        return new RefUpdate(ref, Optional.ofNullable(old), Optional.ofNullable(next));
    }
}
