package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    void transfersOwnershipWithoutConsumingProtocolBytes(boolean memory) throws Exception {
        byte[] wire = pack();
        ByteBuffer source = ByteBuffer.allocate(wire.length + 1).put(wire).put((byte) 42).flip();
        IndexedPack target = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("pack"));
        try (target; BufferedByteInputV2 input = input(source)) {
            try (PackIngestor ingestor = new PackIngestor(input, target)) {
                assertThat(ingestor.ingest()).isSameAs(target);
                ByteBuffer received = ByteBuffer.allocate(wire.length);
                assertThat(target.read(0, received)).isEqualTo(wire.length);
                assertThat(received.array()).containsExactly(wire);
                IndexedPack.EntryMetadata entry = target.find(12).orElseThrow();
                assertThat(entry.offset()).isEqualTo(12);
                assertThat(entry.dataOffset()).isEqualTo(13);
                assertThat(entry.inflatedSize()).isEqualTo(3);
                assertThat(entry.type()).isEqualTo(GitObjectType.BLOB);
                MessageDigest hash = MessageDigest.getInstance("SHA-1");
                hash.update("blob 3\0".getBytes(StandardCharsets.US_ASCII));
                assertThat(target.find(new ObjectId(hash.digest(new byte[]{1, 2, 3})))).contains(entry);
            }
            assertThat(target.isOpen()).isTrue();
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksumFailureDiscardsTheTargetAndLeavesTheInputOpen(boolean memory) throws Exception {
        byte[] wire = pack();
        wire[wire.length - 1] ^= 1;
        ByteBuffer source = ByteBuffer.allocate(wire.length + 1).put(wire).put((byte) 42).flip();
        IndexedPack target = memory ? IndexedPack.create() : IndexedPack.create(directory.resolve("pack"));
        try (target; BufferedByteInputV2 input = input(source)) {
            try (PackIngestor ingestor = new PackIngestor(input, target)) {
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class)
                        .hasMessage("Pack checksum mismatch");
            }
            assertThat(target.isOpen()).isFalse();
            assertThat(Files.exists(directory.resolve("pack"))).isFalse();
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
        try (IndexedPack target = IndexedPack.create();
             BufferedByteInputV2 input = input(ByteBuffer.wrap(PackTestData.join(wire, new byte[]{42})), chunkSize);
             PackIngestor ingestor = new PackIngestor(input, target)) {
            ingestor.ingest();
            assertThat(PackTestData.bytes(target)).containsExactly(wire);
            long offset = 12;
            for (int i = 0; i < types.length; i++) {
                IndexedPack.EntryMetadata entry = target.find(offset).orElseThrow();
                assertThat(entry.type()).isEqualTo(types[i]);
                assertThat(entry.inflatedSize()).isEqualTo(contents.get(i).length);
                assertThat(target.find(PackTestData.objectId(types[i], contents.get(i)))).contains(entry);
                offset += entries.get(i).length;
            }
            assertThat(input.readUnsignedByte()).isEqualTo(42);
            assertThatThrownBy(ingestor::ingest).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void keepsDeltaInstructionsAndBaseReferencesWithoutResolvingThem() throws Exception {
        byte[] base = PackTestData.blob(new byte[]{1, 2, 3});
        byte[] instructions = {3, 3, (byte) 0x90, 3};
        ObjectId baseId = new ObjectId("12".repeat(20));
        byte[] ofs = PackTestData.join(new byte[]{0x64, (byte) base.length},
                PackTestData.compressed(instructions));
        byte[] wire = PackTestData.pack(base, ofs, PackTestData.delta(baseId, instructions));
        try (IndexedPack target = PackTestData.ingest(wire, IndexedPack.create());
             PackUploadIndex state = PackUploadIndex.create(target)) {
            assertThat(target.entryCount()).isEqualTo(3);
            assertThat(target.objectCount()).isEqualTo(1);
            assertThat(state.hasUnresolved()).isTrue();
            assertThat(target.find(12 + base.length).orElseThrow().baseOffset()).hasValue(12);
            assertThat(target.find(12 + base.length + ofs.length).orElseThrow().baseId()).contains(baseId);
            assertThat(PackTestData.bytes(target)).containsExactly(wire);
        }
    }

    @Test
    void rejectsEveryTruncatedPrefixWithoutAllowingASecondAttempt() throws Exception {
        byte[] wire = PackTestData.pack(PackTestData.delta(new ObjectId(new byte[20]), new byte[]{1, 1, 1, 9}));
        for (int length = 0; length < wire.length; length++) {
            try (IndexedPack target = IndexedPack.create();
                 BufferedByteInputV2 input = input(ByteBuffer.wrap(Arrays.copyOf(wire, length)));
                 PackIngestor ingestor = new PackIngestor(input, target)) {
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
            try (IndexedPack target = IndexedPack.create();
                 BufferedByteInputV2 input = input(ByteBuffer.wrap(PackTestData.pack(entry)));
                 PackIngestor ingestor = new PackIngestor(input, target)) {
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class);
                assertThat(target.entryCount()).isZero();
            }
        }
    }

    @Test
    void acceptsEmptyPacksAndRejectsInvalidHeaders() throws Exception {
        try (IndexedPack target = PackTestData.ingest(PackTestData.pack(), IndexedPack.create())) {
            assertThat(target.entryCount()).isZero();
            assertThat(target.id()).isNotNull();
        }
        byte[] magic = PackTestData.pack();
        magic[0] = 'X';
        byte[] version = PackTestData.pack();
        version[7] = 3;
        byte[] unsignedCount = ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(-1).array();
        for (byte[] bytes : new byte[][]{magic, version, unsignedCount}) {
            assertThatThrownBy(() -> PackTestData.ingest(bytes, IndexedPack.create()))
                    .isInstanceOf(IOException.class);
        }
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
