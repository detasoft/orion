package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.ExistsGitObjectRead;
import pro.deta.orion.git.parser.v2.read.RawGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexedPackTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsAcceptedWritesWithoutChangingTheNextAppendOffset(boolean memory) throws Exception {
        byte[] content = {1, 2, 3};
        byte[] compressed = compressed(content);
        Path staging = directory.resolve("staging");
        try (IndexedPack pack = memory ? IndexedPack.create() : IndexedPack.create(staging)) {
            pack.append(ByteBuffer.allocate(13));
            pack.append(ByteBuffer.wrap(compressed));
            pack.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
            long end = pack.size();
            assertThat(pack.readObject(12, new ContentGitObjectRead<byte[]>(
                    (type, size, base, input) -> input.readBytes((int) size)))).containsExactly(content);
            assertThat(pack.readObject(12, new RawGitObjectRead<byte[]>(
                    (type, size, base, input) -> input.readBytes(compressed.length)))).containsExactly(compressed);
            pack.append(ByteBuffer.wrap(new byte[]{42}));
            assertThat(pack.size()).isEqualTo(end + 1);
            ByteBuffer last = ByteBuffer.allocate(1);
            assertThat(pack.read(end, last)).isEqualTo(1);
            assertThat(last.array()).containsExactly(42);
        }
    }

    @Test
    void reopensBytesAndObjectIndexTogetherAndRejectsMutations() throws Exception {
        Path staging = directory.resolve("staging");
        ObjectId id;
        PackId packId;
        try (IndexedPack pack = IndexedPack.create(staging)) {
            id = writeBlob(pack);
            packId = new GitPackObjectResolver(pack, new GitStorageApi()).complete();
        }
        try (IndexedPack pack = IndexedPack.open(staging.resolve("data.pack"), staging.resolve("data.mv"))) {
            assertThat(pack.id()).isEqualTo(packId);
            assertThat(pack.find(id)).contains(entry());
            assertThat(pack.readObject(id, new ContentGitObjectRead<byte[]>(
                    (type, size, base, input) -> input.readBytes((int) size))))
                    .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
            assertThatThrownBy(() -> pack.append(ByteBuffer.wrap(new byte[]{42})))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.addEntry(12, 13, 3, GitObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.truncate(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(pack::discard).isInstanceOf(IllegalStateException.class);
        }
        assertThat(staging.resolve("data.pack")).exists();
        assertThat(staging.resolve("data.mv")).exists();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readerFailureDoesNotPoisonThePack(boolean memory) throws Exception {
        try (IndexedPack pack = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("staging"))) {
            writeBlob(pack);
            IOException failure = new IOException("reader failed");
            assertThatThrownBy(() -> pack.readObject(12, (type, size, base, input) -> {
                throw failure;
            })).isSameAs(failure);
            assertThat(pack.readObject(12, new ExistsGitObjectRead())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsTruncatedDataAndClosesBothResources(boolean memory) throws Exception {
        IndexedPack pack = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("staging"));
        try (pack) {
            pack.append(ByteBuffer.allocate(13));
            pack.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
            assertThatThrownBy(() -> pack.readObject(12, new ExistsGitObjectRead()))
                    .isInstanceOf(EOFException.class);
            pack.truncate(12);
            assertThatThrownBy(() -> pack.readObject(12, new ExistsGitObjectRead()))
                    .isInstanceOf(EOFException.class);
        }
        assertThatThrownBy(() -> pack.readObject(12, new ExistsGitObjectRead()))
                .isInstanceOf(ClosedChannelException.class);
        pack.discard();
        pack.discard();
        assertThat(directory.resolve("staging")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesUnreadPayloadAndClosesAnUnreturnedResult(boolean memory) throws Exception {
        byte[] compressed = compressed(new byte[]{1, 2, 3});
        compressed[compressed.length - 1] ^= 1;
        try (IndexedPack pack = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("staging"))) {
            pack.append(ByteBuffer.allocate(13));
            pack.append(ByteBuffer.wrap(compressed));
            pack.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
            boolean[] closed = {false};
            assertThatThrownBy(() -> pack.readObject(12,
                    (type, size, base, input) -> (AutoCloseable) () -> {
                        closed[0] = true;
                        throw new IOException("cleanup failure");
                    })).isInstanceOf(IOException.class)
                    .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
            assertThat(closed[0]).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistPublishesThePreparedPackAndTransfersItsFilesToStorage(boolean memory) throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        IndexedPack pack = memory ? IndexedPack.create() : storage.newPack();
        ObjectId id = writeBlob(pack);
        Path staging = memory ? null : pack.directory();
        PackId expected = new GitPackObjectResolver(pack, storage).complete();
        assertThat(storage.persist(pack)).isEqualTo(expected);
        pack.close();
        pack.discard();
        if (staging != null) {
            assertThat(staging).doesNotExist();
        }
        assertThat(storage.exists(id)).isTrue();
        assertThat(storage.readObject(id, (type, size, base, input) -> storage.exists(id))).contains(true);
        assertThat(storage.readObject(id, new ContentGitObjectRead<byte[]>(
                (type, size, base, input) -> input.readBytes((int) size))))
                .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionLocksIdentityBytesAndIndexIncludingCopies(boolean memory) throws Exception {
        try (GitStorageApi storage = new GitStorageApi();
             IndexedPack pack = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("staging"))) {
            ObjectId object = writeBlob(pack);
            assertThatThrownBy(pack::id).isInstanceOf(IOException.class).hasMessageContaining("not completed");
            PackId id = new GitPackObjectResolver(pack, storage).complete();
            assertThat(pack.id()).isEqualTo(id);
            long size = pack.size();
            assertThatThrownBy(() -> pack.append(ByteBuffer.wrap(new byte[]{1})))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.write(8, ByteBuffer.allocate(4)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.truncate(12)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.addEntry(12, 13, 3, GitObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.addObject(12, object, GitObjectType.BLOB, 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new GitPackObjectResolver(pack, storage).complete())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(pack.id()).isEqualTo(id);
            assertThat(pack.size()).isEqualTo(size);
            assertThat(pack.objectCount()).isEqualTo(1);
            try (IndexedPack copy = pack.copy();
                 IndexedPack diskCopy = pack.copyTo(directory.resolve("copy"))) {
                assertThat(copy.id()).isEqualTo(id);
                assertThat(diskCopy.id()).isEqualTo(id);
                assertThatThrownBy(() -> copy.truncate(0)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> diskCopy.truncate(0)).isInstanceOf(IllegalStateException.class);
                assertThat(storage.persist(copy)).isEqualTo(id);
                assertThat(storage.exists(object)).isTrue();
                diskCopy.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistRejectsAnUncompletedPackAndDiscardsOnlyTheAttempt(boolean memory) throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        IndexedPack pack = memory ? IndexedPack.create() : storage.newPack();
        ObjectId id = writeBlob(pack);
        Path staging = memory ? null : pack.directory();
        assertThatThrownBy(() -> storage.persist(pack)).isInstanceOf(IOException.class);
        if (staging != null) {
            assertThat(staging).doesNotExist();
        }
        assertThat(storage.exists(id)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsAndRewritesAcrossStorageBlocksAndTruncates(boolean memory) throws Exception {
        try (IndexedPack pack = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("staging"))) {
            byte[] expected = new byte[20000];
            new Random(42).nextBytes(expected);
            ByteBuffer source = ByteBuffer.allocateDirect(expected.length).put(expected).flip();
            pack.append(source.asReadOnlyBuffer());
            ByteBuffer actual = ByteBuffer.allocateDirect(expected.length);
            assertThat(pack.read(0, actual)).isEqualTo(expected.length);
            assertThat(actual.flip()).isEqualTo(ByteBuffer.wrap(expected));
            pack.write(8191, ByteBuffer.wrap(new byte[]{7, 8, 9}));
            ByteBuffer boundary = ByteBuffer.allocate(3);
            assertThat(pack.read(8191, boundary)).isEqualTo(3);
            assertThat(boundary.array()).containsExactly(7, 8, 9);
            pack.truncate(8192);
            pack.append(ByteBuffer.wrap(new byte[]{10}));
            assertThat(pack.size()).isEqualTo(8193);
            ByteBuffer tail = ByteBuffer.allocate(2);
            assertThat(pack.read(8191, tail)).isEqualTo(2);
            assertThat(tail.array()).containsExactly(7, 10);
            assertThat(pack.read(pack.size(), ByteBuffer.allocate(1))).isEqualTo(-1);
            assertThatThrownBy(() -> pack.write(-1, ByteBuffer.allocate(0)))
                    .isInstanceOf(IllegalArgumentException.class);
            pack.flush();
        }
    }

    @Test
    void memoryStorageUsesLongOffsetsAndDoesNotRetainTruncatedBytes() throws Exception {
        try (IndexedPack pack = IndexedPack.create()) {
            long offset = (long) Integer.MAX_VALUE + 100;
            pack.write(offset, ByteBuffer.wrap(new byte[]{1, 2, 3}));
            assertThat(pack.size()).isEqualTo(offset + 3);
            ByteBuffer data = ByteBuffer.allocate(4);
            assertThat(pack.read(offset - 1, data)).isEqualTo(4);
            assertThat(data.array()).containsExactly(0, 1, 2, 3);
            pack.truncate(offset);
            pack.write(offset + 2, ByteBuffer.wrap(new byte[]{9}));
            data.clear();
            assertThat(pack.read(offset - 1, data)).isEqualTo(4);
            assertThat(data.array()).containsExactly(0, 0, 0, 9);
            assertThat(pack.isInMemory()).isTrue();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                assertThat(files.iterator().hasNext()).isFalse();
            }
        }
    }

    @Test
    void completesTemporaryResolutionStateInMemoryWithoutClosingThePack() throws Exception {
        try (IndexedPack pack = IndexedPack.create()) {
            ObjectId id = writeBlob(pack);
            try (PackUploadIndex state = PackUploadIndex.create(pack)) {
                assertThat(state.hasUnresolved()).isFalse();
                state.finish();
            }
            assertThat(pack.find(id)).contains(entry());
            assertThat(pack.readObject(id, new ExistsGitObjectRead())).contains(true);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                assertThat(files.iterator().hasNext()).isFalse();
            }
        }
    }

    private static ObjectId writeBlob(IndexedPack pack) throws Exception {
        byte[] content = {1, 2, 3};
        byte[] compressed = compressed(content);
        ByteBuffer wire = ByteBuffer.allocate(13 + compressed.length);
        wire.putInt(0x5041434b).putInt(2).putInt(1).put((byte) 0x33).put(compressed);
        pack.append(wire.flip());
        pack.append(ByteBuffer.wrap(MessageDigest.getInstance("SHA-1").digest(wire.array())));
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        hash.update("blob 3\0".getBytes(StandardCharsets.US_ASCII));
        ObjectId id = new ObjectId(hash.digest(content));
        pack.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
        pack.addObject(12, id, GitObjectType.BLOB, content.length);
        return id;
    }

    private static IndexedPack.EntryMetadata entry() {
        return new IndexedPack.EntryMetadata(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
    }

    private static byte[] compressed(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(content);
        }
        return bytes.toByteArray();
    }
}
