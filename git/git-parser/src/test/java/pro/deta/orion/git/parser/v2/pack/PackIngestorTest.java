package pro.deta.orion.git.parser.v2.pack;

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
        return new BufferedByteInputV2(new BufferedByteInputV2.Source() {
            @Override
            public ByteBuffer read() {
                if (!source.hasRemaining()) {
                    return null;
                }
                int length = Math.min(7, source.remaining());
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
