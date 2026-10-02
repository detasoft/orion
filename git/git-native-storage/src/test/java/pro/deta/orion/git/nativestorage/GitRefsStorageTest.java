package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.RefSelection;
import pro.deta.orion.schema.orion.v2.RepositoryName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

class GitRefsStorageTest {
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final RefId OTHER = new RefId("refs/heads/other");
    private static final ObjectId MISSING = new ObjectId("f".repeat(40));

    @TempDir
    Path repository;
    private NativeGitRepository nativeRepository;
    private GitIndexApi factory;

    @BeforeEach
    void openRepository() {
        nativeRepository = NativeGitRepository.openLocal(
                RepositoryName.parse("test"), repository, MAIN.value());
        factory = nativeRepository.index();
    }

    @AfterEach
    void closeRepository() {
        nativeRepository.close();
    }

    @Test
    void sharesRefsAndBothFormsOfHeadAcrossAccesses() throws Exception {
        factory.withAccess(index -> {
            assertThat(index.snapshotRefs(new RefSelection.All())).isEqualTo(new RefsSnapshot(Map.of(), new Head.Symbolic(MAIN)));
            ObjectId first = publish("first");
            ObjectId second = publish("second");
            assertThat(nativeRepository.publishRefs(List.of(create(MAIN, first)), true))
                    .extracting(RefUpdateResult::status).containsExactly(APPLIED);
            RefsSnapshot before = index.snapshotRefs(new RefSelection.All());
            nativeRepository.publishRefs(
                    List.of(new RefUpdate(MAIN, Optional.of(first), Optional.of(second))), true);
            {
                GitIndexAccess reopenedIndex = factory.createAccess();
                try {
                    assertThat(reopenedIndex.snapshotRefs(new RefSelection.All()).refs())
                            .containsExactlyEntriesOf(Map.of(MAIN, second));
                    assertThat(before.refs()).containsExactlyEntriesOf(Map.of(MAIN, first));

                    Head detached = new Head.Detached(new CommitId(second.toBytes()));
                    updateHead(detached);
                    assertThat(index.snapshotRefs(new RefSelection.All()).head()).isEqualTo(detached);
                    updateHead(new Head.Symbolic(OTHER));
                    assertThat(reopenedIndex.snapshotRefs(new RefSelection.All()).head()).isEqualTo(new Head.Symbolic(OTHER));
                    nativeRepository.publishRefs(
                            List.of(new RefUpdate(MAIN, Optional.of(second), Optional.empty())), true);
                    assertThat(reopenedIndex.snapshotRefs(new RefSelection.All()).refs()).isEmpty();
                    assertThat(Files.isRegularFile(repository.resolve("refs.mv"))).isTrue();
                } finally {
                    reopenedIndex.discard();
                }
            }
            return null;
        });
    }

    @Test
    void atomicBatchAbortsWhileNonAtomicBatchAppliesValidUpdates() throws Exception {
        factory.withAccess(index -> {
            ObjectId first = publish("first");
            ObjectId second = publish("second");
            nativeRepository.publishRefs(List.of(create(MAIN, first)), true);
            List<RefUpdate> updates = List.of(
                    new RefUpdate(MAIN, Optional.of(second), Optional.of(first)), create(OTHER, second));
            assertThat(nativeRepository.publishRefs(updates, true)).extracting(RefUpdateResult::status)
                    .containsExactly(EXPECTED_OLD_MISMATCH, ATOMIC_ABORTED);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs())
                    .containsExactlyEntriesOf(Map.of(MAIN, first));
            assertThat(nativeRepository.publishRefs(updates, false)).extracting(RefUpdateResult::status)
                    .containsExactly(EXPECTED_OLD_MISMATCH, APPLIED);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs()).containsExactlyInAnyOrderEntriesOf(
                    Map.of(MAIN, first, OTHER, second));
            assertThat(nativeRepository.publishRefs(List.of(create(MAIN, first)), true))
                    .extracting(RefUpdateResult::status).containsExactly(EXPECTED_OLD_MISMATCH);
            return null;
        });
    }

    @Test
    void missingObjectsAbortAtomicBatchAndDuplicateRefsAreRejected() throws Exception {
        factory.withAccess(index -> {
            ObjectId first = publish("first");
            assertThat(nativeRepository.publishRefs(List.of(create(MAIN, first), create(OTHER, MISSING)), true))
                    .extracting(RefUpdateResult::status).containsExactly(ATOMIC_ABORTED, OBJECT_NOT_FOUND);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs()).isEmpty();
            assertThatThrownBy(() -> nativeRepository.publishRefs(
                    List.of(create(MAIN, first), create(MAIN, first)), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> nativeRepository.publishRefs(
                    List.of(create(new RefId("HEAD"), first)), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs()).isEmpty();
            assertThat(nativeRepository.publishRefs(
                    List.of(create(MAIN, first), create(OTHER, MISSING)), false))
                    .extracting(RefUpdateResult::status).containsExactly(APPLIED, OBJECT_NOT_FOUND);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs()).containsExactlyEntriesOf(Map.of(MAIN, first));
            return null;
        });
    }

    @Test
    void objectValidationPreservesResultOrderAndDoesNotRequireObjectsForDeletion() throws Exception {
        factory.withAccess(index -> {
            ObjectId first = publish("first");
            ObjectId second = publish("second");
            RefId stale = new RefId("refs/heads/stale");
            RefId created = new RefId("refs/heads/created");
            nativeRepository.publishRefs(List.of(create(MAIN, first), create(stale, first)), true);
            List<RefUpdate> updates = List.of(
                    create(OTHER, MISSING),
                    new RefUpdate(MAIN, Optional.of(first), Optional.empty()),
                    new RefUpdate(stale, Optional.of(second), Optional.of(first)),
                    create(created, second));

            List<RefUpdateResult> atomic = nativeRepository.publishRefs(updates, true);
            assertThat(atomic).extracting(RefUpdateResult::update).containsExactlyElementsOf(updates);
            assertThat(atomic).extracting(RefUpdateResult::status)
                    .containsExactly(OBJECT_NOT_FOUND, ATOMIC_ABORTED, ATOMIC_ABORTED, ATOMIC_ABORTED);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(MAIN, first, stale, first));

            List<RefUpdateResult> independent = nativeRepository.publishRefs(updates, false);
            assertThat(independent).extracting(RefUpdateResult::update).containsExactlyElementsOf(updates);
            assertThat(independent).extracting(RefUpdateResult::status)
                    .containsExactly(OBJECT_NOT_FOUND, APPLIED, EXPECTED_OLD_MISMATCH, APPLIED);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(stale, first, created, second));
            return null;
        });
    }

    @Test
    void rejectsDuplicateRefsEvenWhenOneTargetObjectIsMissing() throws Exception {
        factory.withAccess(index -> {
            ObjectId first = publish("first");
            assertThatThrownBy(() -> nativeRepository.publishRefs(
                    List.of(create(MAIN, MISSING), create(MAIN, first)), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(index.snapshotRefs(new RefSelection.All()).refs()).isEmpty();
            return null;
        });
    }

    @Test
    void concurrentVirtualThreadUpdatesCompareAgainstThePublishedValue() throws Exception {
        factory.withAccess(reader -> {
            ObjectId first = publish("first");
            ObjectId second = publish("second");
            ObjectId third = publish("third");
            nativeRepository.publishRefs(List.of(create(MAIN, first)), true);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<RefUpdateResult>> left = executor.submit(() -> {
                    start.await();
                    return nativeRepository.publishRefs(List.of(
                            new RefUpdate(MAIN, Optional.of(first), Optional.of(second))), true);
                });
                Future<List<RefUpdateResult>> right = executor.submit(() -> {
                    start.await();
                    return nativeRepository.publishRefs(List.of(
                            new RefUpdate(MAIN, Optional.of(first), Optional.of(third))), true);
                });
                start.countDown();
                assertThat(List.of(left.get(10, TimeUnit.SECONDS).getFirst().status(),
                        right.get(10, TimeUnit.SECONDS).getFirst().status()))
                        .containsExactlyInAnyOrder(APPLIED, EXPECTED_OLD_MISMATCH);
            }
            assertThat(reader.findRef(MAIN).orElseThrow()).isIn(second, third);
            return null;
        });
    }

    @Test
    void snapshotsNeverObservePartOfAnAtomicBatch() throws Exception {
        factory.withAccess(readerIndex -> {
            ObjectId first = publish("first");
            ObjectId second = publish("second");
            assertThat(nativeRepository.publishRefs(List.of(create(MAIN, first), create(OTHER, first)), true))
                    .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> writing = executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 20; iteration++) {
                        ObjectId previous = iteration % 2 == 0 ? first : second;
                        ObjectId next = iteration % 2 == 0 ? second : first;
                        assertThat(nativeRepository.publishRefs(List.of(
                                new RefUpdate(MAIN, Optional.of(previous), Optional.of(next)),
                                new RefUpdate(OTHER, Optional.of(previous), Optional.of(next))), true))
                                .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
                    }
                    return null;
                });
                Future<?> reading = executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 40; iteration++) {
                        RefsSnapshot snapshot = readerIndex.snapshotRefs(new RefSelection.All());
                        assertThat(snapshot.refs().get(MAIN)).isEqualTo(snapshot.refs().get(OTHER));
                        assertThat(snapshot.head()).isEqualTo(new Head.Symbolic(MAIN));
                    }
                    return null;
                });
                start.countDown();
                writing.get(10, TimeUnit.SECONDS);
                reading.get(10, TimeUnit.SECONDS);
            }
            return null;
        });
    }

    @Test
    void unreadableStoreIsReportedWhenOpeningAccess() throws Exception {
        publish("first");
        Files.delete(repository.resolve("refs.mv"));
        Files.createDirectory(repository.resolve("refs.mv"));
        assertThatThrownBy(factory::createAccess).isInstanceOf(IOException.class)
                .hasMessageContaining("Repository index file is missing");
    }

    private void updateHead(Head head) throws IOException {
        factory.withAccess(access -> {
            access.updateHead(head);
            access.apply();
            return null;
        });
    }

    private static RefUpdate create(RefId ref, ObjectId id) {
        return new RefUpdate(ref, Optional.empty(), Optional.of(id));
    }

    private ObjectId publish(String message) {
        byte[] content = ("tree " + "0".repeat(40) + "\n\n" + message + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        return nativeRepository.writeObject(GitObjectType.COMMIT, content);
    }
}
