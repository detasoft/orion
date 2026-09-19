package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexedPackTest {
    @TempDir
    Path directory;

    @Test
    void readsAcceptedWritesWithoutChangingTheNextAppendOffset() throws Exception {
        byte[] content = {1, 2, 3};
        byte[] compressed = compressed(content);
        Path staging = directory.resolve("staging");
        try (IndexedPack pack = IndexedPack.create(staging)) {
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
            assertThat(Files.readAllBytes(staging.resolve("data.pack"))[(int) end]).isEqualTo((byte) 42);
        }
    }

    @Test
    void reopensBytesAndObjectIndexTogetherAndRejectsMutations() throws Exception {
        Path staging = directory.resolve("staging");
        ObjectId id;
        PackId packId;
        try (IndexedPack pack = IndexedPack.create(staging)) {
            id = writeBlob(pack);
            packId = pack.id();
            pack.flush();
        }
        try (IndexedPack pack = IndexedPack.open(staging.resolve("data.pack"), staging.resolve("data.mv"))) {
            assertThat(pack.id()).isEqualTo(packId);
            assertThat(pack.find(id)).contains(entry());
            assertThat(pack.readObject(id, new ContentGitObjectRead<byte[]>(
                    (type, size, base, input) -> input.readBytes((int) size))))
                    .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
            assertThatThrownBy(() -> pack.append(ByteBuffer.wrap(new byte[]{42})))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pack.truncate(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(pack::discard).isInstanceOf(IllegalStateException.class);
        }
        assertThat(staging.resolve("data.pack")).exists();
        assertThat(staging.resolve("data.mv")).exists();
    }

    @Test
    void readerFailureDoesNotPoisonThePack() throws Exception {
        try (IndexedPack pack = IndexedPack.create(directory.resolve("staging"))) {
            writeBlob(pack);
            IOException failure = new IOException("reader failed");
            assertThatThrownBy(() -> pack.readObject(12, (type, size, base, input) -> {
                throw failure;
            })).isSameAs(failure);
            assertThat(pack.readObject(12, new ExistsGitObjectRead())).isTrue();
        }
    }

    @Test
    void reportsTruncatedDataAndClosesBothResources() throws Exception {
        IndexedPack pack = IndexedPack.create(directory.resolve("staging"));
        try (pack) {
            pack.append(ByteBuffer.allocate(13));
            pack.addEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty());
            assertThatThrownBy(() -> pack.readObject(12, new ExistsGitObjectRead()))
                    .isInstanceOf(EOFException.class);
        }
        assertThatThrownBy(() -> pack.readObject(12, new ExistsGitObjectRead()))
                .isInstanceOf(ClosedChannelException.class);
        pack.discard();
        pack.discard();
        assertThat(directory.resolve("staging")).doesNotExist();
    }

    @Test
    void validatesUnreadPayloadAndClosesAnUnreturnedResult() throws Exception {
        byte[] compressed = compressed(new byte[]{1, 2, 3});
        compressed[compressed.length - 1] ^= 1;
        try (IndexedPack pack = IndexedPack.create(directory.resolve("staging"))) {
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

    @Test
    void persistPublishesThePreparedPackAndTransfersItsFilesToStorage() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        IndexedPack pack = storage.newPack();
        ObjectId id = writeBlob(pack);
        Path staging = pack.directory();
        PackId expected = pack.id();
        assertThat(storage.persist(pack)).isEqualTo(expected);
        pack.close();
        pack.discard();
        assertThat(staging).doesNotExist();
        assertThat(storage.exists(id)).isTrue();
        assertThat(storage.readObject(id, (type, size, base, input) -> storage.exists(id))).contains(true);
        assertThat(storage.readObject(id, new ContentGitObjectRead<byte[]>(
                (type, size, base, input) -> input.readBytes((int) size))))
                .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
    }

    @Test
    void persistRejectsAnInvalidChecksumAndDiscardsOnlyTheAttempt() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        IndexedPack pack = storage.newPack();
        ObjectId id = writeBlob(pack);
        Path staging = pack.directory();
        byte last = pack.id().toBytes()[19];
        pack.write(pack.size() - 1, ByteBuffer.wrap(new byte[]{(byte) (last ^ 1)}));
        assertThatThrownBy(() -> storage.persist(pack)).isInstanceOf(IOException.class);
        assertThat(staging).doesNotExist();
        assertThat(storage.exists(id)).isFalse();
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
