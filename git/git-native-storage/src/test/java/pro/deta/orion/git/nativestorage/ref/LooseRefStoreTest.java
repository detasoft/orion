package pro.deta.orion.git.nativestorage.ref;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.GitObjectId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LooseRefStoreTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String SHA1_A = "a".repeat(40);
    private static final String SHA1_B = "b".repeat(40);
    private static final String SHA1_C = "c".repeat(40);

    private final LooseRefStore store = new LooseRefStore();

    @Test
    void readReturnsEmptyForAbsentRef() {
        assertThat(store.read("refs/heads/main")).isEmpty();
    }

    @Test
    void createRefWithNullOldId() {
        RefUpdateResult result = store.update("refs/heads/main", NULL_ID, SHA1_A);

        assertThat(result).isEqualTo(RefUpdateResult.CREATED);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void createRefFailsIfRefAlreadyExists() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", NULL_ID, SHA1_B);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void updateRefWithMatchingOldId() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, SHA1_B);

        assertThat(result).isEqualTo(RefUpdateResult.FAST_FORWARD);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_B));
    }

    @Test
    void updateRefFailsWithStaleOldId() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_B, SHA1_C);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void deletesRefWithMatchingOldId() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, NULL_ID);

        assertThat(result).isEqualTo(RefUpdateResult.DELETED);
        assertThat(store.read("refs/heads/main")).isEmpty();
    }

    @Test
    void updateNonExistentRefWithNonNullOldIdIsStale() {
        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, SHA1_B);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(store.read("refs/heads/main")).isEmpty();
    }

    @Test
    void snapshotReturnsCurrentRefs() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        store.update("refs/heads/feature", NULL_ID, SHA1_B);

        var snapshot = store.snapshot();

        assertThat(snapshot).containsEntry("refs/heads/main", SHA1_A);
        assertThat(snapshot).containsEntry("refs/heads/feature", SHA1_B);
    }

    @Test
    void snapshotIsImmutableCopy() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        var snapshot = store.snapshot();

        store.update("refs/heads/main", SHA1_A, SHA1_B);

        assertThat(snapshot).containsEntry("refs/heads/main", SHA1_A);
    }

    @Test
    void noOpWhenCreatingRefWithSameTargetThatAlreadyExists() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", NULL_ID, SHA1_A);

        assertThat(result).isEqualTo(RefUpdateResult.NO_OP);
    }

    @Test
    void noOpWhenUpdatingRefToSameTarget() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, SHA1_A);

        assertThat(result).isEqualTo(RefUpdateResult.NO_OP);
    }

    @Test
    void batchPublishesObjectsBeforeMakingSuccessfulRefsVisible() {
        AtomicBoolean objectsPublished = new AtomicBoolean();

        List<RefUpdateResult> results = store.updateAll(
                List.of(new LooseRefStore.Update("refs/heads/main", NULL_ID, SHA1_A)),
                () -> {
                    assertThat(store.read("refs/heads/main")).isEmpty();
                    objectsPublished.set(true);
                });

        assertThat(results).containsExactly(RefUpdateResult.CREATED);
        assertThat(objectsPublished).isTrue();
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void independentBatchPublishesObjectsBeforeUpdatingEachRef() {
        AtomicBoolean objectsPublished = new AtomicBoolean();

        List<RefUpdateResult> results = store.updateAllIndependently(
                List.of(new LooseRefStore.Update("refs/heads/main", NULL_ID, SHA1_A)),
                () -> {
                    assertThat(store.read("refs/heads/main")).isEmpty();
                    objectsPublished.set(true);
                });

        assertThat(results).containsExactly(RefUpdateResult.CREATED);
        assertThat(objectsPublished).isTrue();
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void batchDoesNotPublishObjectsWhenEveryUpdateIsStale() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        AtomicBoolean objectsPublished = new AtomicBoolean();

        List<RefUpdateResult> results = store.updateAll(
                List.of(new LooseRefStore.Update("refs/heads/main", SHA1_B, SHA1_C)),
                () -> objectsPublished.set(true));

        assertThat(results).containsExactly(RefUpdateResult.STALE);
        assertThat(objectsPublished).isFalse();
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void batchDoesNotApplyAnyRefWhenOneUpdateIsStale() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        AtomicBoolean objectsPublished = new AtomicBoolean();

        List<RefUpdateResult> results = store.updateAll(
                List.of(
                        new LooseRefStore.Update("refs/heads/feature", NULL_ID, SHA1_B),
                        new LooseRefStore.Update("refs/heads/main", SHA1_C, SHA1_B)),
                () -> objectsPublished.set(true));

        assertThat(results).containsExactly(RefUpdateResult.CREATED, RefUpdateResult.STALE);
        assertThat(objectsPublished).isFalse();
        assertThat(store.read("refs/heads/feature")).isEmpty();
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void persistsRefsAcrossStoreInstances(@TempDir Path repositoryDirectory) {
        LooseRefStore writer = new LooseRefStore(repositoryDirectory);

        RefUpdateResult result = writer.update(
                "refs/heads/main",
                NULL_ID,
                SHA1_A);

        LooseRefStore reader = new LooseRefStore(repositoryDirectory);
        assertThat(result).isEqualTo(RefUpdateResult.CREATED);
        assertThat(reader.read("refs/heads/main"))
                .contains(GitObjectId.of(SHA1_A));
        assertThat(Files.isRegularFile(
                repositoryDirectory.resolve("refs/heads/main"))).isTrue();
    }

    @Test
    void longLivedStoreRefreshesExternallyUpdatedRefs(@TempDir Path repositoryDirectory) {
        LooseRefStore reader = new LooseRefStore(repositoryDirectory);
        LooseRefStore writer = new LooseRefStore(repositoryDirectory);

        writer.update("refs/heads/main", NULL_ID, SHA1_A);

        assertThat(reader.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
        assertThat(reader.snapshot()).containsEntry("refs/heads/main", SHA1_A);
    }

    @Test
    void staleStoreCannotReplaceExternallyUpdatedRef(@TempDir Path repositoryDirectory) throws Exception {
        LooseRefStore first = new LooseRefStore(repositoryDirectory);
        LooseRefStore second = new LooseRefStore(repositoryDirectory);
        first.update("refs/heads/main", NULL_ID, SHA1_A);
        second.read("refs/heads/main");
        first.update("refs/heads/main", SHA1_A, SHA1_B);

        RefUpdateResult result = second.update("refs/heads/main", SHA1_A, SHA1_C);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(first.read("refs/heads/main")).contains(GitObjectId.of(SHA1_B));
        assertThat(Files.readString(repositoryDirectory.resolve("refs/heads/main")).trim())
                .isEqualTo(SHA1_B);
    }

    @Test
    void concurrentStoresAcceptExactlyOneUpdate(@TempDir Path repositoryDirectory) throws Exception {
        LooseRefStore first = new LooseRefStore(repositoryDirectory);
        LooseRefStore second = new LooseRefStore(repositoryDirectory);
        first.update("refs/heads/main", NULL_ID, SHA1_A);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> updateAfterStart(first, ready, start, SHA1_B));
            var secondResult = executor.submit(() -> updateAfterStart(second, ready, start, SHA1_C));
            ready.await();
            start.countDown();

            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder(RefUpdateResult.FAST_FORWARD, RefUpdateResult.STALE);
        }
        assertThat(first.read("refs/heads/main").orElseThrow().value()).isIn(SHA1_B, SHA1_C);
    }

    @Test
    void removesPersistedRefOnDelete(@TempDir Path repositoryDirectory) {
        LooseRefStore writer = new LooseRefStore(repositoryDirectory);
        writer.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = writer.update(
                "refs/heads/main",
                SHA1_A,
                NULL_ID);

        LooseRefStore reader = new LooseRefStore(repositoryDirectory);
        assertThat(result).isEqualTo(RefUpdateResult.DELETED);
        assertThat(reader.read("refs/heads/main")).isEmpty();
        assertThat(Files.exists(
                repositoryDirectory.resolve("refs/heads/main"))).isFalse();
    }

    @Test
    void ignoresTemporaryRefFilesWhenLoading(
            @TempDir Path repositoryDirectory) throws Exception {
        Path refsDirectory = repositoryDirectory.resolve("refs/heads");
        Files.createDirectories(refsDirectory);
        Files.writeString(
                refsDirectory.resolve("main.tmp-1"),
                SHA1_A + "\n");

        LooseRefStore reader = new LooseRefStore(repositoryDirectory);

        assertThat(reader.snapshot()).isEmpty();
    }

    private static RefUpdateResult updateAfterStart(
            LooseRefStore store,
            CountDownLatch ready,
            CountDownLatch start,
            String newId) throws InterruptedException {
        ready.countDown();
        start.await();
        return store.update("refs/heads/main", SHA1_A, newId);
    }
}
