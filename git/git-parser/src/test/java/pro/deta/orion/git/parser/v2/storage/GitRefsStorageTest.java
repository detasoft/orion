package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

class GitRefsStorageTest implements BufferedByteInputV2.Source {
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final RefId OTHER = new RefId("refs/heads/other");
    private static final ObjectId MISSING = new ObjectId("f".repeat(40));

    @TempDir
    Path repository;
    private ByteBuffer source;

    @Test
    void persistsRefsAndBothFormsOfHeadAcrossFacadeInstances() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        assertThat(storage.snapshotRefs()).isEqualTo(new RefsSnapshot(Map.of(), new Head.Symbolic(MAIN)));
        ObjectId first = publish(storage, "first");
        ObjectId second = publish(storage, "second");
        assertThat(storage.updateRefs(List.of(create(MAIN, first)), true))
                .extracting(RefUpdateResult::status).containsExactly(APPLIED);
        RefsSnapshot before = storage.snapshotRefs();
        storage.updateRefs(List.of(new RefUpdate(MAIN, Optional.of(first), Optional.of(second))), true);
        GitStorageApi reopened = new GitStorageApi(repository);
        assertThat(reopened.snapshotRefs().refs()).containsExactlyEntriesOf(Map.of(MAIN, second));
        assertThat(before.refs()).containsExactlyEntriesOf(Map.of(MAIN, first));

        Head detached = new Head.Detached(new CommitId(second.toBytes()));
        reopened.updateHead(detached);
        assertThat(storage.snapshotRefs().head()).isEqualTo(detached);
        storage.updateHead(new Head.Symbolic(OTHER));
        assertThat(new GitStorageApi(repository).snapshotRefs().head()).isEqualTo(new Head.Symbolic(OTHER));
        storage.updateRefs(List.of(new RefUpdate(MAIN, Optional.of(second), Optional.empty())), true);
        assertThat(reopened.snapshotRefs().refs()).isEmpty();
        assertThat(Files.isRegularFile(repository.resolve("refs.mv"))).isTrue();
    }

    @Test
    void atomicBatchAbortsWhileNonAtomicBatchAppliesValidUpdates() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        ObjectId first = publish(storage, "first");
        ObjectId second = publish(storage, "second");
        storage.updateRefs(List.of(create(MAIN, first)), true);
        List<RefUpdate> updates = List.of(
                new RefUpdate(MAIN, Optional.of(second), Optional.of(first)), create(OTHER, second));
        assertThat(storage.updateRefs(updates, true)).extracting(RefUpdateResult::status)
                .containsExactly(EXPECTED_OLD_MISMATCH, ATOMIC_ABORTED);
        assertThat(new GitStorageApi(repository).snapshotRefs().refs())
                .containsExactlyEntriesOf(Map.of(MAIN, first));
        assertThat(storage.updateRefs(updates, false)).extracting(RefUpdateResult::status)
                .containsExactly(EXPECTED_OLD_MISMATCH, APPLIED);
        assertThat(storage.snapshotRefs().refs()).containsExactlyInAnyOrderEntriesOf(
                Map.of(MAIN, first, OTHER, second));
        assertThat(storage.updateRefs(List.of(create(MAIN, first)), true))
                .extracting(RefUpdateResult::status).containsExactly(EXPECTED_OLD_MISMATCH);
    }

    @Test
    void missingObjectsAbortAtomicBatchAndDuplicateRefsAreRejected() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        ObjectId first = publish(storage, "first");
        assertThat(storage.updateRefs(List.of(create(MAIN, first), create(OTHER, MISSING)), true))
                .extracting(RefUpdateResult::status).containsExactly(ATOMIC_ABORTED, OBJECT_NOT_FOUND);
        assertThat(storage.snapshotRefs().refs()).isEmpty();
        assertThatThrownBy(() -> storage.updateRefs(List.of(create(MAIN, first), create(MAIN, first)), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.updateRefs(List.of(create(new RefId("HEAD"), first)), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.snapshotRefs().refs()).isEmpty();
        assertThatThrownBy(() -> storage.updateHead(new Head.Detached(new CommitId(MISSING.toBytes()))))
                .isInstanceOf(java.io.IOException.class);
        assertThat(storage.snapshotRefs().head()).isEqualTo(new Head.Symbolic(MAIN));
        assertThat(storage.updateRefs(List.of(create(MAIN, first), create(OTHER, MISSING)), false))
                .extracting(RefUpdateResult::status).containsExactly(APPLIED, OBJECT_NOT_FOUND);
        assertThat(storage.snapshotRefs().refs()).containsExactlyEntriesOf(Map.of(MAIN, first));
    }

    @Test
    void concurrentVirtualThreadUpdatesCompareAgainstThePublishedValue() throws Exception {
        GitStorageApi firstStorage = new GitStorageApi(repository);
        GitStorageApi secondStorage = new GitStorageApi(repository);
        ObjectId first = publish(firstStorage, "first");
        ObjectId second = publish(firstStorage, "second");
        ObjectId third = publish(firstStorage, "third");
        firstStorage.updateRefs(List.of(create(MAIN, first)), true);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<RefUpdateResult>> left = executor.submit(() -> {
                start.await();
                return firstStorage.updateRefs(List.of(
                        new RefUpdate(MAIN, Optional.of(first), Optional.of(second))), true);
            });
            Future<List<RefUpdateResult>> right = executor.submit(() -> {
                start.await();
                return secondStorage.updateRefs(List.of(
                        new RefUpdate(MAIN, Optional.of(first), Optional.of(third))), true);
            });
            start.countDown();
            assertThat(List.of(left.get(10, TimeUnit.SECONDS).getFirst().status(),
                    right.get(10, TimeUnit.SECONDS).getFirst().status()))
                    .containsExactlyInAnyOrder(APPLIED, EXPECTED_OLD_MISMATCH);
        }
        assertThat(firstStorage.snapshotRefs().refs().get(MAIN)).isIn(second, third);
    }

    @Test
    void snapshotsNeverObservePartOfAnAtomicBatch() throws Exception {
        GitStorageApi writer = new GitStorageApi(repository);
        GitStorageApi reader = new GitStorageApi(repository);
        ObjectId first = publish(writer, "first");
        ObjectId second = publish(writer, "second");
        assertThat(writer.updateRefs(List.of(create(MAIN, first), create(OTHER, first)), true))
                .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writing = executor.submit(() -> {
                start.await();
                for (int iteration = 0; iteration < 20; iteration++) {
                    ObjectId previous = iteration % 2 == 0 ? first : second;
                    ObjectId next = iteration % 2 == 0 ? second : first;
                    assertThat(writer.updateRefs(List.of(
                            new RefUpdate(MAIN, Optional.of(previous), Optional.of(next)),
                            new RefUpdate(OTHER, Optional.of(previous), Optional.of(next))), true))
                            .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
                }
                return null;
            });
            Future<?> reading = executor.submit(() -> {
                start.await();
                for (int iteration = 0; iteration < 40; iteration++) {
                    RefsSnapshot snapshot = reader.snapshotRefs();
                    assertThat(snapshot.refs().get(MAIN)).isEqualTo(snapshot.refs().get(OTHER));
                    assertThat(snapshot.head()).isEqualTo(new Head.Symbolic(MAIN));
                }
                return null;
            });
            start.countDown();
            writing.get(10, TimeUnit.SECONDS);
            reading.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void unreadableStoreIsReportedInsteadOfReturningAnEmptySnapshot() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        Files.delete(repository.resolve("refs.mv"));
        Files.createDirectory(repository.resolve("refs.mv"));
        assertThatThrownBy(storage::snapshotRefs).isInstanceOf(java.io.IOException.class);
        assertThat(storage.updateRefs(List.of(create(MAIN, MISSING)), true))
                .extracting(RefUpdateResult::status).containsExactly(STORAGE_ERROR);
    }

    private static RefUpdate create(RefId ref, ObjectId id) {
        return new RefUpdate(ref, Optional.empty(), Optional.of(id));
    }

    private ObjectId publish(GitStorageApi storage, String message) throws Exception {
        byte[] content = ("tree " + "0".repeat(40) + "\n\n" + message + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
        digest.update(("commit " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        ObjectId id = new ObjectId(digest.digest(content));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             BufferedByteInputV2 input = new BufferedByteInputV2(
                     new ByteArrayInputStream(content))) {
            writer.writeObject(GitObjectType.COMMIT, content.length, input);
            writer.finish();
        }
        source = ByteBuffer.wrap(bytes.toByteArray());
        try (BufferedByteInputV2 input = new BufferedByteInputV2(this);
             PackIngestor ingestor = new PackIngestor(input, storage.newPack())) {
            storage.persist(ingestor.ingest());
        }
        return id;
    }

    @Override
    public ByteBuffer read() {
        return source.hasRemaining() ? source : null;
    }

    @Override
    public void release() {}

    @Override
    public void close() {}
}
