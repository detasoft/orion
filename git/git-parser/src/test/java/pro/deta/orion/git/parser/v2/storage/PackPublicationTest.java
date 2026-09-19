package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PackPublicationTest {
    @TempDir
    Path directory;

    @Test
    void publishesOnlyAtPersistAndSurvivesReopen() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        byte[] wire = pack(blob(new byte[]{1, 2, 3}));
        ObjectId object = objectId(GitObjectType.BLOB, new byte[]{1, 2, 3});
        try (BufferedByteInputV2 source = source(join(wire, new byte[]{42}));
             IndexedPack target = storage.newPack();
             PackIngestor ingestor = new PackIngestor(source, target)) {
            ingestor.ingest();
            assertThat(storage.exists(object)).isFalse();
            assertThat(storage.findPacksByObjectIds(List.of(object))).isEmpty();
            PackId id = target.id();
            assertThat(storage.persist(target)).isEqualTo(id);
            assertThat(Files.readAllBytes(path(id, ".pack"))).containsExactly(wire);
            assertThat(path(id, ".mv")).isRegularFile();
            assertThat(source.readUnsignedByte()).isEqualTo(42);
            GitStorageApi reopened = new GitStorageApi(directory);
            assertThat(reopened.readObject(object, new HashedGitObjectRead())).contains(object);
            assertThat(reopened.findPacksByObjectIds(List.of(object))).containsEntry(object, List.of(id));
            assertThatThrownBy(target::size).isInstanceOf(ClosedChannelException.class);
        }
        assertStagingEmpty();
    }

    @Test
    void discardingOneAttemptDoesNotRemoveAnother() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        try (IndexedPack first = ingest(pack(blob(new byte[]{1})), storage.newPack());
             IndexedPack second = ingest(pack(blob(new byte[]{2})), storage.newPack())) {
            first.discard();
            first.discard();
            storage.persist(second);
            assertThat(storage.exists(blobId((byte) 2))).isTrue();
        }
        assertStagingEmpty();
    }

    @Test
    void concurrentIdenticalPublicationsReuseOnePairAcrossStorageInstances() throws Exception {
        byte[] wire = pack(blob(new byte[]{5}));
        GitStorageApi firstStorage = new GitStorageApi(directory);
        GitStorageApi secondStorage = new GitStorageApi(directory);
        try (IndexedPack first = ingest(wire, firstStorage.newPack());
             IndexedPack second = ingest(wire, secondStorage.newPack());
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PackId expected = first.id();
            CyclicBarrier start = new CyclicBarrier(2);
            Future<PackId> a = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return firstStorage.persist(first);
            });
            Future<PackId> b = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return secondStorage.persist(second);
            });
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            ObjectId object = blobId((byte) 5);
            assertThat(firstStorage.findPacksByObjectIds(List.of(object)))
                    .containsEntry(object, List.of(expected));
        }
        assertStagingEmpty();
    }

    @Test
    void incompletePublicationIsInvisibleAndCanBeReplacedByVerifiedPack() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        try (IndexedPack target = ingest(pack(blob(new byte[]{7})), storage.newPack())) {
            PackId id = target.id();
            Files.createDirectories(path(id, ".pack").getParent());
            Files.write(path(id, ".pack"), new byte[]{0});
            assertThat(storage.exists(blobId((byte) 7))).isFalse();
            storage.persist(target);
            assertThat(storage.readObject(blobId((byte) 7), new HashedGitObjectRead())).contains(blobId((byte) 7));
        }
        assertStagingEmpty();
    }

    @Test
    void failedParsingLeavesNoPublishedOrTemporaryFiles() throws Exception {
        byte[] wire = pack(blob(new byte[]{8}));
        wire[wire.length - 1] ^= 1;
        GitStorageApi storage = new GitStorageApi(directory);
        assertThatThrownBy(() -> ingest(wire, storage.newPack())).isInstanceOf(IOException.class);
        assertThat(storage.exists(blobId((byte) 8))).isFalse();
        assertStagingEmpty();
    }

    @Test
    void completesThinPackFromTwoPublishedBasesAndIndexesTheFinalPackId() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        for (byte value : new byte[]{1, 2}) {
            storage.persist(ingest(pack(blob(new byte[]{value})), storage.newPack()));
        }
        ObjectId firstBase = blobId((byte) 1);
        ObjectId secondBase = blobId((byte) 2);
        byte[] firstDelta = delta(firstBase, new byte[]{1, 1, 1, 3});
        byte[] thin = pack(firstDelta, delta(secondBase, new byte[]{1, 1, 1, 4}));
        try (IndexedPack target = ingest(thin, storage.newPack())) {
            target.addObject(12, blobId((byte) 3), GitObjectType.BLOB, 1);
            target.addObject(12 + firstDelta.length, blobId((byte) 4), GitObjectType.BLOB, 1);
            PackId received = target.id();
            PackId completed = new GitPackObjectResolver(target, storage).complete();
            assertThat(storage.persist(target)).isEqualTo(completed);
            assertThat(completed).isNotEqualTo(received);
            assertThat(path(received, ".mv")).doesNotExist();
            assertThat(ByteBuffer.wrap(Files.readAllBytes(path(completed, ".pack"))).getInt(8)).isEqualTo(4);
            assertThat(storage.findPacksByObjectIds(List.of(firstBase, secondBase)))
                    .allSatisfy((id, packs) -> assertThat(packs).hasSize(2).contains(completed));
            GitStorageApi reopened = new GitStorageApi(directory);
            assertThat(reopened.readObject(blobId((byte) 4), new ResolvedGitObjectRead<>(reopened,
                    (type, size, base, content) -> content.readBytes((int) size))))
                    .hasValueSatisfying(content -> assertThat(content).containsExactly((byte) 4));
        }
        assertStagingEmpty();
    }

    @Test
    void publishedIndexCorruptionIsAnErrorRatherThanAnAbsentObject() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        PackId id = storage.persist(ingest(pack(blob(new byte[]{9})), storage.newPack()));
        Files.write(path(id, ".mv"), new byte[]{0});
        assertThatThrownBy(() -> storage.exists(blobId((byte) 9))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.findPacksByObjectIds(List.of(blobId((byte) 9))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void publicationFailureCleansStagingWithoutDeletingExistingPaths() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        try (IndexedPack target = ingest(pack(blob(new byte[]{10})), storage.newPack())) {
            Path obstacle = path(target.id(), ".mv");
            Files.createDirectories(obstacle);
            Files.write(obstacle.resolve("keep"), new byte[]{42});
            assertThatThrownBy(() -> storage.persist(target)).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(obstacle.resolve("keep"))).containsExactly((byte) 42);
        }
        assertStagingEmpty();
    }

    @Test
    void resolvesAReferenceToAnotherObjectInTheSamePublishedPack() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        byte[] full = blob(new byte[]{1});
        try (IndexedPack target = ingest(pack(full, delta(blobId((byte) 1), new byte[]{1, 1, 1, 2})),
                storage.newPack())) {
            target.addObject(12 + full.length, blobId((byte) 2), GitObjectType.BLOB, 1);
            storage.persist(target);
            assertThat(storage.readObject(blobId((byte) 2), new ResolvedGitObjectRead<>(storage,
                    (type, size, base, content) -> content.readBytes((int) size))))
                    .hasValueSatisfying(content -> assertThat(content).containsExactly((byte) 2));
        }
    }

    @Test
    void concurrentReadersDoNotRetainTheIndexLockAcrossCallbacks() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        PackId id = storage.persist(ingest(pack(blob(new byte[]{1})), storage.newPack()));
        ObjectId object = blobId((byte) 1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<?>> futures = new ArrayList<>();
            for (GitStorageApi api : List.of(storage, new GitStorageApi(directory))) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        assertThat(api.readObject(object, (type, size, base, content) -> {
                            try {
                                barrier.await(5, TimeUnit.SECONDS);
                            } catch (Exception error) {
                                throw new IOException(error);
                            }
                            return true;
                        })).contains(true);
                        assertThat(api.findPacksByObjectIds(List.of(object)))
                                .containsEntry(object, List.of(id));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static ObjectId blobId(byte value) {
        return objectId(GitObjectType.BLOB, new byte[]{value});
    }

    private Path path(PackId id, String extension) {
        String hex = id.toHex();
        return directory.resolve("packs").resolve(hex.substring(0, 2)).resolve(hex.substring(2) + extension);
    }

    private void assertStagingEmpty() throws IOException {
        try (Stream<Path> files = Files.list(directory.resolve("incoming"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private static BufferedByteInputV2 source(byte[] bytes) {
        return new BufferedByteInputV2(new ByteArrayInputStream(bytes));
    }
}
