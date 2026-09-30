package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIngestorTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completesPrivatePackWithoutConsumingProtocolBytes(boolean memory) throws Exception {
        byte[] wire = pack();
        try (GitStorageApi storage = memory ? new InMemoryStorage() : new LocalGitStorage(directory);
             GitIndexAccess index = memory ? new InMemoryIndex().createAccess() : new LocalGitIndex(directory).createAccess();
             BufferedByteInputV2 input = input(ByteBuffer.wrap(PackTestData.join(wire, new byte[]{42})))) {
            PackMetadata pack;
            try (PackIngestor ingestor = new PackIngestor(input, storage, index)) {
                pack = ingestor.ingest();
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IllegalStateException.class);
            }
            assertThat(storage.exists(pack.packId())).isTrue();
            assertThat(index.packs()).isEmpty();
            ObjectId id = PackTestData.objectId(GitObjectType.BLOB, new byte[]{1, 2, 3});
            assertThat(index.locations(id)).isEmpty();
            IndexedObject object = index.findObject(pack.packId(), id).orElseThrow();
            assertThat(object.objectSize()).isEqualTo(3);
            assertThat(object.packOffset()).isEqualTo(8);
            assertThat(PackTestData.bytes(pack, storage, index)).containsExactly(wire);
            index.publishIndex(pack);
            assertThat(index.locations(id)).containsExactly(object);
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192, 262144})
    void hashesLargeAndEmptyObjectsAcrossChunkBoundaries(int chunkSize) throws Exception {
        byte[] random = new byte[200_000];
        new Random(73).nextBytes(random);
        byte[] repeated = new byte[1_000_000];
        Arrays.fill(repeated, (byte) 9);
        List<byte[]> entries = new ArrayList<>();
        List<byte[]> contents = List.of(random, random, repeated, random, new byte[0]);
        GitObjectType[] types = {GitObjectType.COMMIT, GitObjectType.TREE, GitObjectType.BLOB,
                GitObjectType.TAG, GitObjectType.BLOB};
        for (int i = 0; i < types.length; i++) {
            entries.add(PackTestData.entry(types[i], contents.get(i)));
        }
        byte[] wire = PackTestData.pack(entries.toArray(byte[][]::new));
        try (InMemoryStorage storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess();
             BufferedByteInputV2 input = input(ByteBuffer.wrap(PackTestData.join(wire, new byte[]{42})), chunkSize);
             PackIngestor ingestor = new PackIngestor(input, storage, index)) {
            PackMetadata pack = ingestor.ingest();
            assertThat(PackTestData.bytes(pack, storage, index)).containsExactly(wire);
            for (int i = 0; i < types.length; i++) {
                IndexedObject object = index.findObject(pack.packId(),
                        PackTestData.objectId(types[i], contents.get(i))).orElseThrow();
                assertThat(object.type()).isEqualTo(types[i]);
                assertThat(object.objectSize()).isEqualTo(contents.get(i).length);
            }
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void corruptTrailerDoesNotPublishObjectsOrRefsAndLeavesInputOpen(boolean memory) throws Exception {
        byte[] wire = pack();
        wire[wire.length - 1] ^= 1;
        try (GitStorageApi storage = memory ? new InMemoryStorage() : new LocalGitStorage(directory);
             GitIndexAccess index = memory ? new InMemoryIndex().createAccess() : new LocalGitIndex(directory).createAccess();
             BufferedByteInputV2 input = input(ByteBuffer.wrap(PackTestData.join(wire, new byte[]{42})));
             PackIngestor ingestor = new PackIngestor(input, storage, index)) {
            ObjectId previous = PackTestData.store(storage, index, GitObjectType.BLOB, new byte[]{9});
            List<PackMetadata> published = index.packs();
            assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class)
                    .hasMessage("Pack checksum mismatch");
            assertThat(index.packs()).isEqualTo(published);
            assertThat(index.locations(previous)).hasSize(1);
            assertThat(index.locations(PackTestData.objectId(GitObjectType.BLOB, new byte[]{1, 2, 3}))).isEmpty();
            assertThat(index.snapshotRefs().refs()).isEmpty();
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
        }

    @Test
    void rejectsEveryTruncatedPrefixWithoutAllowingASecondAttempt() throws Exception {
        byte[] wire = PackTestData.pack(PackTestData.delta(new ObjectId(new byte[20]), new byte[]{1, 1, 1, 9}));
        for (int length = 0; length < wire.length; length++) {
            try (InMemoryStorage storage = new InMemoryStorage();
                 GitIndexAccess index = new InMemoryIndex().createAccess();
                 BufferedByteInputV2 input = input(ByteBuffer.wrap(Arrays.copyOf(wire, length)));
                 PackIngestor ingestor = new PackIngestor(input, storage, index)) {
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class);
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IllegalStateException.class);
            }
            }
    }

    @Test
    void rejectsMalformedEntriesBeforeRegisteringThem() throws Exception {
        byte[] sizeOverflow = {(byte) 0xb0, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 8};
        byte[] offsetOverflow = {0x60, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0};
        byte[] valid = PackTestData.compressed(new byte[]{1, 2, 3});
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        ByteArrayOutputStream dictionary = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        try {
            deflater.setDictionary(new byte[]{1, 2, 3});
            try (DeflaterOutputStream output = new DeflaterOutputStream(dictionary, deflater)) {
                output.write(new byte[]{1, 2, 3});
            }
        } finally {
            deflater.end();
        }
        for (byte[] entry : new byte[][]{{0}, {0x50}, {0x60, 0}, {0x60, 1}, {0x60, 13},
                sizeOverflow, offsetOverflow, PackTestData.join(new byte[]{0x32}, valid),
                PackTestData.join(new byte[]{0x34}, valid), PackTestData.join(new byte[]{0x33}, corrupt),
                PackTestData.join(new byte[]{0x33}, dictionary.toByteArray())}) {
            try (InMemoryStorage storage = new InMemoryStorage();
                 GitIndexAccess index = new InMemoryIndex().createAccess();
                 BufferedByteInputV2 input = input(ByteBuffer.wrap(PackTestData.pack(entry)));
                 PackIngestor ingestor = new PackIngestor(input, storage, index)) {
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class);
                assertThat(index.packs()).isEmpty();
            }
            }
    }

    @Test
    void acceptsEmptyPacksAndRejectsInvalidHeaders() throws Exception {
        try (InMemoryStorage storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess()) {
            PackMetadata pack = PackTestData.ingest(PackTestData.pack(), storage, index);
            assertThat(pack.objectCount()).isZero();
            assertThat(PackTestData.bytes(pack, storage, index)).containsExactly(PackTestData.pack());
        }
        byte[] magic = PackTestData.pack();
        magic[0] = 'X';
        byte[] version = PackTestData.pack(4);
        byte[] unsignedCount = ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(-1).array();
        for (byte[] bytes : new byte[][]{magic, version, unsignedCount}) {
            assertThatThrownBy(() -> PackTestData.inspect(bytes))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsCorruptVersionThreeChecksum() throws Exception {
        byte[] corrupt = PackTestData.pack(3, PackTestData.blob(new byte[]{1}));
        corrupt[corrupt.length - 1] ^= 1;
        assertThatThrownBy(() -> PackTestData.inspect(corrupt))
                .isInstanceOf(IOException.class).hasMessage("Pack checksum mismatch");
    }

    private static byte[] pack() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(compressed)) {
            zlib.write(new byte[]{1, 2, 3});
        }
        ByteBuffer body = ByteBuffer.allocate(13 + compressed.size());
        body.putInt(0x5041434b).putInt(2).putInt(1).put((byte) 0x33).put(compressed.toByteArray());
        byte[] checksum = MessageDigest.getInstance("SHA-1").digest(body.array());
        return ByteBuffer.allocate(body.capacity() + checksum.length).put(body.array()).put(checksum).array();
    }

    private static BufferedByteInputV2 input(ByteBuffer source) {
        return input(source, 7);
    }

    private static BufferedByteInputV2 input(ByteBuffer source, int chunkSize) {
        return new BufferedByteInputV2(new BufferedByteInputV2.Source() {
            @Override
            public ByteBuffer read() {
                if (!source.hasRemaining()) {
                    return null;
                }
                int length = Math.min(chunkSize, source.remaining());
                ByteBuffer chunk = source.slice(source.position(), length);
                source.position(source.position() + length);
                return chunk;
            }

            @Override
            public void release() {
            }

            @Override
            public void close() {
            }
        });
    }

}
