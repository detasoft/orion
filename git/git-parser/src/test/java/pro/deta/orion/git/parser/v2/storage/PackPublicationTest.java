package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.pack.PackUpload;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackPublicationTest {
    @TempDir
    Path directory;

    @Test
    void publishesOnlyAtCommitSurvivesReopenAndRollbackKeepsPublishedData() throws Exception {
        var storage = new GitStorageApi(directory);
        byte[] pack = pack(new byte[]{1, 2, 3});
        try (var source = source(join(pack, new byte[]{42}))) {
            var upload = storage.uploadNewPack(source);
            try {
                ObjectId object = upload.next().value().orElseThrow();
                assertThat(upload.hasNext()).isFalse();
                assertThat(storage.exists(object)).isFalse();
                assertThat(storage.findPacksByObjectIds(List.of(object))).isEmpty();
                PackId id = upload.packId();
                upload.commit(id);
                assertThat(Files.readAllBytes(path(id, ".pack"))).containsExactly(pack);
                assertThat(Files.isRegularFile(path(id, ".mv"))).isTrue();
                upload.rollback();
                upload.rollback();
                assertThat(source.readBytes(1)).containsExactly((byte) 42);
                var reopened = new GitStorageApi(directory);
                assertThat(reopened.readObject(object, new HashedGitObjectRead())).contains(object);
                assertThat(reopened.findPacksByObjectIds(List.of(object))).containsEntry(object, List.of(id));
                assertThatThrownBy(upload::next).isInstanceOf(ClosedChannelException.class);
            } finally {
                upload.rollback();
            }
        }
        assertStagingEmpty();
    }

    @Test
    void rollbackDiscardsOnlyItsAttemptAndLeavesInputOpen() throws Exception {
        var storage = new GitStorageApi(directory);
        try (var firstSource = source(pack(new byte[]{1})); var secondSource = source(pack(new byte[]{2}))) {
            var first = storage.uploadNewPack(firstSource);
            var second = storage.uploadNewPack(secondSource);
            try {
                first.next();
                first.rollback();
                first.rollback();
                assertThat(firstSource.readBytes(1)).hasSize(1);
                ObjectId object = second.next().value().orElseThrow();
                assertThat(second.hasNext()).isFalse();
                second.commit(second.packId());
                assertThat(storage.exists(object)).isTrue();
            } finally {
                first.rollback();
                second.rollback();
            }
        }
        assertStagingEmpty();
    }

    @Test
    void concurrentIdenticalPublicationsReuseOnePairAcrossStorageInstances() throws Exception {
        byte[] pack = pack(new byte[]{5});
        try (var sourceA = source(pack); var sourceB = source(pack);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new GitStorageApi(directory).uploadNewPack(sourceA);
            var second = new GitStorageApi(directory).uploadNewPack(sourceB);
            try {
                ObjectId object = first.next().value().orElseThrow();
                second.next();
                assertThat(first.hasNext()).isFalse();
                assertThat(second.hasNext()).isFalse();
                var a = executor.submit(() -> { first.commit(first.packId()); return null; });
                var b = executor.submit(() -> { second.commit(second.packId()); return null; });
                a.get(10, TimeUnit.SECONDS);
                b.get(10, TimeUnit.SECONDS);
                assertThat(new GitStorageApi(directory).findPacksByObjectIds(List.of(object)))
                        .containsEntry(object, List.of(first.packId()));
            } finally {
                first.rollback();
                second.rollback();
            }
        }
        assertStagingEmpty();
    }

    @Test
    void incompletePublicationIsInvisibleAndCanBeReplacedByVerifiedUpload() throws Exception {
        byte[] pack = pack(new byte[]{7});
        var storage = new GitStorageApi(directory);
        try (var source = source(pack)) {
            var upload = storage.uploadNewPack(source);
            try {
                ObjectId object = upload.next().value().orElseThrow();
                assertThat(upload.hasNext()).isFalse();
                PackId id = upload.packId();
                Files.createDirectories(path(id, ".pack").getParent());
                Files.write(path(id, ".pack"), new byte[]{0});
                assertThat(storage.exists(object)).isFalse();
                upload.commit(id);
                assertThat(storage.readObject(object, new HashedGitObjectRead())).contains(object);
            } finally {
                upload.rollback();
            }
        }
    }

    @Test
    void failedParsingCannotPublishAndRollbackRemovesItsFiles() throws Exception {
        byte[] pack = pack(new byte[]{8});
        pack[pack.length - 1] ^= 1;
        var storage = new GitStorageApi(directory);
        try (var source = source(pack)) {
            PackUpload upload = storage.uploadNewPack(source);
            try {
                ObjectId object = upload.next().value().orElseThrow();
                assertThatThrownBy(upload::hasNext).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> upload.commit(new PackId(new byte[20])))
                        .isInstanceOf(IOException.class);
                assertThat(storage.exists(object)).isFalse();
            } finally {
                upload.rollback();
            }
        }
        assertStagingEmpty();
    }

    @Test
    void completesThinPackFromTwoPublishedBasesAndIndexesTheFinalPackId() throws Exception {
        var storage = new GitStorageApi(directory);
        for (byte value : new byte[]{1, 2}) {
            try (var source = source(pack(new byte[]{value}))) {
                var upload = storage.uploadNewPack(source);
                try {
                    upload.next();
                    assertThat(upload.hasNext()).isFalse();
                    upload.commit(upload.packId());
                } finally {
                    upload.rollback();
                }
            }
        }
        ObjectId firstBase = blobId((byte) 1);
        ObjectId secondBase = blobId((byte) 2);
        byte[] thin = entries(delta(firstBase, (byte) 3), delta(secondBase, (byte) 4));
        try (var source = source(thin)) {
            var upload = storage.uploadNewPack(source);
            try {
                for (byte value : new byte[]{3, 4}) {
                    var entry = upload.next().entry();
                    upload.index().addObject(entry, blobId(value), ObjectType.BLOB, 1);
                }
                assertThat(upload.hasNext()).isFalse();
                PackId received = upload.packId();
                upload.commit(received);
                PackId completed = upload.packId();
                assertThat(completed).isNotEqualTo(received);
                assertThat(Files.exists(path(received, ".mv"))).isFalse();
                byte[] bytes = Files.readAllBytes(path(completed, ".pack"));
                assertThat(ByteBuffer.wrap(bytes).getInt(8)).isEqualTo(4);
                assertThat(storage.findPacksByObjectIds(List.of(firstBase, secondBase)))
                        .allSatisfy((id, packs) -> assertThat(packs).hasSize(2).contains(completed));
                var reopened = new GitStorageApi(directory);
                assertThat(reopened.readObject(blobId((byte) 4), new ResolvedGitObjectRead<>(reopened,
                        (type, size, base, content) -> content.readBytes((int) size))))
                        .hasValueSatisfying(content -> assertThat(content).containsExactly((byte) 4));
            } finally {
                upload.rollback();
            }
        }
        assertStagingEmpty();
    }

    @Test
    void publishedIndexCorruptionIsAnErrorRatherThanAnAbsentObject() throws Exception {
        var storage = new GitStorageApi(directory);
        try (var source = source(pack(new byte[]{9}))) {
            var upload = storage.uploadNewPack(source);
            try {
                ObjectId object = upload.next().value().orElseThrow();
                assertThat(upload.hasNext()).isFalse();
                upload.commit(upload.packId());
                Files.write(path(upload.packId(), ".mv"), new byte[]{0});
                assertThatThrownBy(() -> storage.exists(object)).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> storage.findPacksByObjectIds(List.of(object)))
                        .isInstanceOf(IOException.class);
            } finally {
                upload.rollback();
            }
        }
    }

    @Test
    void publicationFailureRemainsRollbackSafeAndDoesNotDeleteExistingPaths() throws Exception {
        var storage = new GitStorageApi(directory);
        try (var source = source(pack(new byte[]{10}))) {
            var upload = storage.uploadNewPack(source);
            try {
                upload.next();
                assertThat(upload.hasNext()).isFalse();
                PackId id = upload.packId();
                Path obstacle = path(id, ".mv");
                Files.createDirectories(obstacle);
                Files.write(obstacle.resolve("keep"), new byte[]{42});
                assertThatThrownBy(() -> upload.commit(id)).isInstanceOf(IOException.class);
                upload.rollback();
                assertThat(Files.readAllBytes(obstacle.resolve("keep"))).containsExactly((byte) 42);
            } finally {
                upload.rollback();
            }
        }
        assertStagingEmpty();
    }

    private static ObjectId blobId(byte value) throws Exception {
        return new ObjectId(MessageDigest.getInstance("SHA-1").digest(
                new byte[]{'b', 'l', 'o', 'b', ' ', '1', 0, value}));
    }

    @Test
    void resolvesAReferenceToAnotherObjectInTheSamePublishedPack() throws Exception {
        var storage = new GitStorageApi(directory);
        byte[] full = join(new byte[]{0x31}, compressed(new byte[]{1}));
        try (var source = source(entries(full, delta(blobId((byte) 1), (byte) 2)))) {
            var upload = storage.uploadNewPack(source);
            try {
                upload.next();
                var entry = upload.next().entry();
                upload.index().addObject(entry, blobId((byte) 2), ObjectType.BLOB, 1);
                assertThat(upload.hasNext()).isFalse();
                upload.commit(upload.packId());
                assertThat(storage.readObject(blobId((byte) 2), new ResolvedGitObjectRead<>(storage,
                        (type, size, base, content) -> content.readBytes((int) size))))
                        .hasValueSatisfying(content -> assertThat(content).containsExactly((byte) 2));
            } finally {
                upload.rollback();
            }
        }
    }

    @Test
    void concurrentReadersDoNotRetainTheIndexLockAcrossCallbacks() throws Exception {
        var storage = new GitStorageApi(directory);
        try (var source = source(pack(new byte[]{1}));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var upload = storage.uploadNewPack(source);
            try {
                ObjectId object = upload.next().value().orElseThrow();
                assertThat(upload.hasNext()).isFalse();
                upload.commit(upload.packId());
                var barrier = new CyclicBarrier(2);
                var other = new GitStorageApi(directory);
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (var api : List.of(storage, other)) {
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
                                    .containsEntry(object, List.of(upload.packId()));
                        }
                        return null;
                    }));
                }
                for (var future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                upload.rollback();
            }
        }
    }

    private static byte[] delta(ObjectId base, byte value) throws Exception {
        return join(new byte[]{0x74}, base.toBytes(), compressed(new byte[]{1, 1, 1, value}));
    }

    private Path path(PackId id, String extension) {
        String hex = id.toHex();
        return directory.resolve("packs").resolve(hex.substring(0, 2)).resolve(hex.substring(2) + extension);
    }

    private void assertStagingEmpty() throws IOException {
        try (var files = Files.list(directory.resolve("incoming"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private static InputStreamBufferedByteInput source(byte[] bytes) {
        return new InputStreamBufferedByteInput(new ByteArrayInputStream(bytes));
    }

    private static byte[] pack(byte[] content) throws Exception {
        return entries(join(new byte[]{(byte) (0x30 | content.length)}, compressed(content)));
    }

    private static byte[] compressed(byte[] content) throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var zlib = new DeflaterOutputStream(compressed)) {
            zlib.write(content);
        }
        return compressed.toByteArray();
    }

    private static byte[] entries(byte[]... entries) throws Exception {
        byte[] body = join(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(entries.length).array(),
                join(entries));
        return join(body, MessageDigest.getInstance("SHA-1").digest(body));
    }

    private static byte[] join(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }
}
