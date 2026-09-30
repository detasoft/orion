package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
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
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import static pro.deta.orion.git.parser.v2.GitRepositoryContext.publishRefs;
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

class GitRefsStorageTest implements BufferedByteInputV2.Source {
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final RefId OTHER = new RefId("refs/heads/other");
    private static final ObjectId MISSING = new ObjectId("f".repeat(40));

    @TempDir
    Path repository;
    private ByteBuffer source;

    private LocalGitIndex factory;

    @BeforeEach
    void openIndex() throws IOException {
        factory = new LocalGitIndex(repository);
    }

    @AfterEach
    void closeIndex() {
        factory.close();
    }

    @Test
    void sharesRefsAndBothFormsOfHeadAcrossAccesses() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        factory.withAccess(index -> {
            assertThat(index.snapshotRefs()).isEqualTo(new RefsSnapshot(Map.of(), new Head.Symbolic(MAIN)));
            ObjectId first = publish(storage, "first");
            ObjectId second = publish(storage, "second");
            assertThat(publishRefs(storage, factory, List.of(create(MAIN, first)), true))
                    .extracting(RefUpdateResult::status).containsExactly(APPLIED);
            RefsSnapshot before = index.snapshotRefs();
            publishRefs(
                    storage, factory,
                    List.of(new RefUpdate(MAIN, Optional.of(first), Optional.of(second))), true);
            GitStorageApi reopened = new LocalGitStorage(repository);
            {
                GitIndexAccess reopenedIndex = factory.createAccess();
                try {
                    assertThat(reopenedIndex.snapshotRefs().refs()).containsExactlyEntriesOf(Map.of(MAIN, second));
                    assertThat(before.refs()).containsExactlyEntriesOf(Map.of(MAIN, first));

                    Head detached = new Head.Detached(new CommitId(second.toBytes()));
                    updateHead(detached);
                    assertThat(index.snapshotRefs().head()).isEqualTo(detached);
                    updateHead(new Head.Symbolic(OTHER));
                    assertThat(reopenedIndex.snapshotRefs().head()).isEqualTo(new Head.Symbolic(OTHER));
                    publishRefs(
                            storage, factory,
                            List.of(new RefUpdate(MAIN, Optional.of(second), Optional.empty())), true);
                    assertThat(reopenedIndex.snapshotRefs().refs()).isEmpty();
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
        GitStorageApi storage = new LocalGitStorage(repository);
        factory.withAccess(index -> {
            ObjectId first = publish(storage, "first");
            ObjectId second = publish(storage, "second");
            publishRefs(storage, factory, List.of(create(MAIN, first)), true);
            List<RefUpdate> updates = List.of(
                    new RefUpdate(MAIN, Optional.of(second), Optional.of(first)), create(OTHER, second));
            assertThat(publishRefs(
                    storage, factory, updates, true)).extracting(RefUpdateResult::status)
                    .containsExactly(EXPECTED_OLD_MISMATCH, ATOMIC_ABORTED);
            assertThat(index.snapshotRefs().refs())
                    .containsExactlyEntriesOf(Map.of(MAIN, first));
            assertThat(publishRefs(
                    storage, factory, updates, false)).extracting(RefUpdateResult::status)
                    .containsExactly(EXPECTED_OLD_MISMATCH, APPLIED);
            assertThat(index.snapshotRefs().refs()).containsExactlyInAnyOrderEntriesOf(
                    Map.of(MAIN, first, OTHER, second));
            assertThat(publishRefs(storage, factory, List.of(create(MAIN, first)), true))
                    .extracting(RefUpdateResult::status).containsExactly(EXPECTED_OLD_MISMATCH);
            return null;
        });
    }

    @Test
    void missingObjectsAbortAtomicBatchAndDuplicateRefsAreRejected() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        factory.withAccess(index -> {
            ObjectId first = publish(storage, "first");
            assertThat(publishRefs(
                    storage, factory, List.of(create(MAIN, first), create(OTHER, MISSING)), true))
                    .extracting(RefUpdateResult::status).containsExactly(ATOMIC_ABORTED, OBJECT_NOT_FOUND);
            assertThat(index.snapshotRefs().refs()).isEmpty();
            assertThatThrownBy(() -> publishRefs(
                    storage, factory, List.of(create(MAIN, first), create(MAIN, first)), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> publishRefs(
                    storage, factory, List.of(create(new RefId("HEAD"), first)), true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(index.snapshotRefs().refs()).isEmpty();
            assertThat(publishRefs(
                    storage, factory, List.of(create(MAIN, first), create(OTHER, MISSING)), false))
                    .extracting(RefUpdateResult::status).containsExactly(APPLIED, OBJECT_NOT_FOUND);
            assertThat(index.snapshotRefs().refs()).containsExactlyEntriesOf(Map.of(MAIN, first));
            return null;
        });
    }

    @Test
    void objectValidationPreservesResultOrderAndDoesNotRequireObjectsForDeletion() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        factory.withAccess(index -> {
            ObjectId first = publish(storage, "first");
            ObjectId second = publish(storage, "second");
            RefId stale = new RefId("refs/heads/stale");
            RefId created = new RefId("refs/heads/created");
            publishRefs(
                    storage, factory, List.of(create(MAIN, first), create(stale, first)), true);
            List<RefUpdate> updates = List.of(
                    create(OTHER, MISSING),
                    new RefUpdate(MAIN, Optional.of(first), Optional.empty()),
                    new RefUpdate(stale, Optional.of(second), Optional.of(first)),
                    create(created, second));

            List<RefUpdateResult> atomic = publishRefs(storage, factory, updates, true);
            assertThat(atomic).extracting(RefUpdateResult::update).containsExactlyElementsOf(updates);
            assertThat(atomic).extracting(RefUpdateResult::status)
                    .containsExactly(OBJECT_NOT_FOUND, ATOMIC_ABORTED, ATOMIC_ABORTED, ATOMIC_ABORTED);
            assertThat(index.snapshotRefs().refs()).containsExactlyInAnyOrderEntriesOf(Map.of(MAIN, first, stale, first));

            List<RefUpdateResult> independent = publishRefs(
                    storage, factory, updates, false);
            assertThat(independent).extracting(RefUpdateResult::update).containsExactlyElementsOf(updates);
            assertThat(independent).extracting(RefUpdateResult::status)
                    .containsExactly(OBJECT_NOT_FOUND, APPLIED, EXPECTED_OLD_MISMATCH, APPLIED);
            assertThat(index.snapshotRefs().refs())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(stale, first, created, second));
            return null;
        });
    }

    @Test
    void rejectsDuplicateRefsEvenWhenOneTargetObjectIsMissing() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        factory.withAccess(index -> {
            ObjectId first = publish(storage, "first");
            assertThatThrownBy(() -> publishRefs(
                    storage, factory, List.of(create(MAIN, MISSING), create(MAIN, first)), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(index.snapshotRefs().refs()).isEmpty();
            return null;
        });
    }

    @Test
    void concurrentVirtualThreadUpdatesCompareAgainstThePublishedValue() throws Exception {
        GitStorageApi firstStorage = new LocalGitStorage(repository);
        {
            GitIndexAccess firstStorageIndex = factory.createAccess();
            try {
                GitStorageApi secondStorage = new LocalGitStorage(repository);
                {
                    GitIndexAccess secondStorageIndex = factory.createAccess();
                    try {
                        ObjectId first = publish(firstStorage, "first");
                        ObjectId second = publish(firstStorage, "second");
                        ObjectId third = publish(firstStorage, "third");
                        publishRefs(firstStorage, factory, List.of(create(MAIN, first)), true);
                        CountDownLatch start = new CountDownLatch(1);
                        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            Future<List<RefUpdateResult>> left = executor.submit(() -> {
                                start.await();
                                return publishRefs(firstStorage, factory, List.of(
                                        new RefUpdate(MAIN, Optional.of(first), Optional.of(second))), true);
                            });
                            Future<List<RefUpdateResult>> right = executor.submit(() -> {
                                start.await();
                                return publishRefs(secondStorage, factory, List.of(
                                        new RefUpdate(MAIN, Optional.of(first), Optional.of(third))), true);
                            });
                            start.countDown();
                            assertThat(List.of(left.get(10, TimeUnit.SECONDS).getFirst().status(),
                                    right.get(10, TimeUnit.SECONDS).getFirst().status()))
                                    .containsExactlyInAnyOrder(APPLIED, EXPECTED_OLD_MISMATCH);
                        }
                        assertThat(firstStorageIndex.snapshotRefs().refs().get(MAIN)).isIn(second, third);
                    } finally {
                        secondStorageIndex.discard();
                    }
                }
            } finally {
                firstStorageIndex.discard();
            }
        }
    }

    @Test
    void snapshotsNeverObservePartOfAnAtomicBatch() throws Exception {
        GitStorageApi writer = new LocalGitStorage(repository);
        {
            GitIndexAccess writerIndex = factory.createAccess();
            try {
                GitStorageApi reader = new LocalGitStorage(repository);
                {
                    GitIndexAccess readerIndex = factory.createAccess();
                    try {
                        ObjectId first = publish(writer, "first");
                        ObjectId second = publish(writer, "second");
                        assertThat(publishRefs(
                                writer, factory,
                                List.of(create(MAIN, first), create(OTHER, first)), true))
                                .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
                        CountDownLatch start = new CountDownLatch(1);
                        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            Future<?> writing = executor.submit(() -> {
                                start.await();
                                for (int iteration = 0; iteration < 20; iteration++) {
                                    ObjectId previous = iteration % 2 == 0 ? first : second;
                                    ObjectId next = iteration % 2 == 0 ? second : first;
                                    assertThat(publishRefs(writer, factory, List.of(
                                            new RefUpdate(MAIN, Optional.of(previous), Optional.of(next)),
                                            new RefUpdate(OTHER, Optional.of(previous), Optional.of(next))), true))
                                            .extracting(RefUpdateResult::status).containsExactly(APPLIED, APPLIED);
                                }
                                return null;
                            });
                            Future<?> reading = executor.submit(() -> {
                                start.await();
                                for (int iteration = 0; iteration < 40; iteration++) {
                                    RefsSnapshot snapshot = readerIndex.snapshotRefs();
                                    assertThat(snapshot.refs().get(MAIN)).isEqualTo(snapshot.refs().get(OTHER));
                                    assertThat(snapshot.head()).isEqualTo(new Head.Symbolic(MAIN));
                                }
                                return null;
                            });
                            start.countDown();
                            writing.get(10, TimeUnit.SECONDS);
                            reading.get(10, TimeUnit.SECONDS);
                        }
                    } finally {
                        readerIndex.discard();
                    }
                }
            } finally {
                writerIndex.discard();
            }
        }
    }

    @Test
    void unreadableStoreIsReportedWhenOpeningAccess() throws Exception {
        try (GitStorageApi storage = new LocalGitStorage(repository)) {
            factory.withAccess(index -> {
                publish(storage, "first");
                return null;
            });
            Files.delete(repository.resolve("refs.mv"));
            Files.createDirectory(repository.resolve("refs.mv"));
            assertThatThrownBy(factory::createAccess).isInstanceOf(IOException.class)
                    .hasMessageContaining("Repository index file is missing");
        }
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
        factory.withAccess(Optional.of(PackId.create()), access -> {
            try (BufferedByteInputV2 input = new BufferedByteInputV2(this);
                 PackIngestor ingestor = new PackIngestor(input, storage, access)) {
                access.publishIndex(ingestor.ingest());
            }
            return null;
        });
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
