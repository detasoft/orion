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
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

class InMemoryStorageTest {
    @TempDir
    Path directory;
    private static final RefId FIRST = new RefId("refs/heads/first");
    private static final RefId SECOND = new RefId("refs/heads/second");

    @Test
    void completesSeveralExternalBasesIdenticallyAndTransfersBetweenBackends() throws Exception {
        try (GitStorageApi memory = new InMemoryStorage(); GitStorageApi disk = new LocalGitStorage(directory)) {
            List<ObjectId> bases = new ArrayList<>();
            for (byte value : new byte[]{1, 2, 3}) {
                bases.add(PackTestData.store(memory, GitObjectType.BLOB, new byte[]{value}));
                PackTestData.store(disk, GitObjectType.BLOB, new byte[]{value});
            }
            bases.sort(Comparator.comparing(ObjectId::toHex).reversed());
            byte[][] deltas = new byte[bases.size()][];
            for (int i = 0; i < deltas.length; i++) {
                deltas[i] = PackTestData.delta(bases.get(i), new byte[]{1, 1, 1, (byte) (10 + i)});
            }
            byte[] source = PackTestData.pack(deltas);
            try (IndexedPack memoryPack = PackTestData.ingest(source, memory.newPack());
                 IndexedPack diskPack = PackTestData.ingest(source, disk.newPack())) {
                PackId completed = new GitPackObjectResolver(memoryPack, memory).complete();
                assertThat(new GitPackObjectResolver(diskPack, disk).complete()).isEqualTo(completed);
                assertThat(PackTestData.bytes(memoryPack)).isEqualTo(PackTestData.bytes(diskPack));
                IndexedPack toDisk = PackTestData.ingest(memoryPack, disk.newPack());
                IndexedPack toMemory = PackTestData.ingest(diskPack, memory.newPack());
                for (IndexedPack replay : List.of(toDisk, toMemory)) {
                    assertThat(new GitPackObjectResolver(replay, memory).complete()).isEqualTo(completed);
                    assertThat(replay.objectIds()).isEqualTo(memoryPack.objectIds());
                    assertThat(replay.checksumMatches(completed)).isTrue();
                }
                disk.persist(toDisk);
                memory.persist(toMemory);
                for (GitStorageApi storage : List.of(memory, disk)) {
                    ObjectId result = PackTestData.objectId(GitObjectType.BLOB, new byte[]{12});
                    assertThat(storage.readObject(result, new ResolvedGitObjectRead<byte[]>(storage,
                            (type, size, base, input) -> input.readBytes((int) size))))
                            .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(12));
                }
            }
        }
    }

    @Test
    void preservesRefResultOrderAndAtomicFailurePrecedence() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage()) {
            ObjectId old = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{1});
            ObjectId next = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{2});
            ObjectId absent = new ObjectId("a".repeat(40));
            assertThat(storage.updateRefs(List.of(update(FIRST, null, old)), true).getFirst().status())
                    .isEqualTo(APPLIED);
            RefsSnapshot before = storage.snapshotRefs();
            List<RefUpdate> changes = List.of(update(SECOND, null, next), update(FIRST, next, old));
            assertThat(storage.updateRefs(changes, true)).extracting(RefUpdateResult::status)
                    .containsExactly(ATOMIC_ABORTED, EXPECTED_OLD_MISMATCH);
            assertThat(storage.snapshotRefs()).isEqualTo(before);
            assertThat(storage.updateRefs(List.of(update(SECOND, null, absent), update(FIRST, next, old)), true))
                    .extracting(RefUpdateResult::status).containsExactly(OBJECT_NOT_FOUND, ATOMIC_ABORTED);
            assertThat(storage.updateRefs(changes, false)).extracting(RefUpdateResult::status)
                    .containsExactly(APPLIED, EXPECTED_OLD_MISMATCH);
            assertThat(before.refs()).containsExactly(Map.entry(FIRST, old));
            assertThat(storage.snapshotRefs().refs()).containsEntry(SECOND, next);
            assertThat(storage.updateRefs(List.of(update(SECOND, next, null)), false).getFirst().status())
                    .isEqualTo(APPLIED);
            assertThat(storage.snapshotRefs()).isEqualTo(before);
            assertThatThrownBy(() -> storage.updateRefs(List.of(update(FIRST, old, next),
                    update(FIRST, old, next)), true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void admitsOnlyOneConcurrentExpectedOldWinner() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ObjectId old = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{1});
            ObjectId next = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{2});
            storage.updateRefs(List.of(update(FIRST, null, old)), true);
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<RefUpdateResult.Status>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                attempts.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return storage.updateRefs(List.of(update(FIRST, old, next)), true).getFirst().status();
                }));
            }
            assertThat(List.of(attempts.get(0).get(5, TimeUnit.SECONDS), attempts.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(APPLIED, EXPECTED_OLD_MISMATCH);
        }
    }

    @Test
    void snapshotsNeverExposePartOfAnAtomicBatch() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ObjectId first = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{1});
            ObjectId second = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{2});
            storage.updateRefs(List.of(update(FIRST, null, first), update(SECOND, null, first)), true);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> writer = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 1000; i++) {
                    ObjectId old = i % 2 == 0 ? first : second;
                    ObjectId next = i % 2 == 0 ? second : first;
                    assertThat(storage.updateRefs(List.of(update(FIRST, old, next), update(SECOND, old, next)), true))
                            .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
                }
                return null;
            });
            Future<?> reader = executor.submit(() -> {
                start.countDown();
                for (int i = 0; i < 2000; i++) {
                    Map<RefId, ObjectId> refs = storage.snapshotRefs().refs();
                    assertThat(refs.get(FIRST)).isEqualTo(refs.get(SECOND));
                }
                return null;
            });
            reader.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void callbacksReleaseRepositoryLockAndFailuresPreservePublishedPacks() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ObjectId object = PackTestData.store(storage, GitObjectType.BLOB, new byte[]{1});
            PackId published = storage.packIds().getFirst();
            assertThat(storage.readObject(object, (type, size, base, input) -> {
                try {
                    return executor.submit(() -> storage.updateRefs(List.of(update(FIRST, null, object)), true))
                            .get(5, TimeUnit.SECONDS).getFirst().status();
                } catch (Exception failure) {
                    throw new IOException(failure);
                }
            })).contains(APPLIED);
            IndexedPack unfinished = storage.newPack();
            assertThatThrownBy(() -> storage.persist(unfinished)).isInstanceOf(IOException.class);
            assertThatThrownBy(unfinished::size).isInstanceOf(ClosedChannelException.class);
            assertThat(storage.packIds()).containsExactly(published);
            assertThat(storage.exists(object)).isTrue();
        }
    }

    @Test
    void isolatesRepositoriesValidatesHeadAndConsumesPublicationAfterClose() throws Exception {
        InMemoryStorage first = new InMemoryStorage();
        try (first; GitStorageApi second = new InMemoryStorage()) {
            ObjectId object = PackTestData.store(first, GitObjectType.BLOB, new byte[]{1});
            Head detached = new Head.Detached(new CommitId(object.toBytes()));
            assertThatThrownBy(() -> second.updateHead(detached)).isInstanceOf(IOException.class);
            first.updateHead(detached);
            assertThat(first.snapshotRefs().head()).isEqualTo(detached);
            assertThat(second.snapshotRefs().head()).isEqualTo(new Head.Symbolic(new RefId("refs/heads/main")));
            assertThat(second.exists(object)).isFalse();
            assertThat(second.packIds()).isEmpty();
            assertThatThrownBy(() -> first.updateHead(new Head.Symbolic(new RefId("HEAD"))))
                    .isInstanceOf(IllegalArgumentException.class);
            IndexedPack attempt = PackTestData.ingest(PackTestData.pack(PackTestData.blob(new byte[]{2})),
                    first.newPack());
            new GitPackObjectResolver(attempt, first).complete();
            first.close();
            assertThatThrownBy(() -> first.persist(attempt)).isInstanceOf(ClosedChannelException.class);
            assertThatThrownBy(attempt::size).isInstanceOf(ClosedChannelException.class);
            assertThatThrownBy(first::snapshotRefs).isInstanceOf(ClosedChannelException.class);
            assertThatThrownBy(first::newPack).isInstanceOf(ClosedChannelException.class);
            assertThat(second.packIds()).isEmpty();
        }
    }

    private static RefUpdate update(RefId ref, ObjectId old, ObjectId next) {
        return new RefUpdate(ref, Optional.ofNullable(old), Optional.ofNullable(next));
    }
}
