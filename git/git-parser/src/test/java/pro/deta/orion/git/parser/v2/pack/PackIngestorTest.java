package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIngestorTest {
    @Test
    void copiesIntoANonFileTargetAndTransfersOwnershipWithoutConsumingProtocolBytes() throws Exception {
        byte[] wire = pack();
        ByteBuffer source = ByteBuffer.allocate(wire.length + 1).put(wire).put((byte) 42).flip();
        MemoryTarget target = new MemoryTarget();
        try (BufferedByteInputV2 input = input(source)) {
            try (PackIngestor<MemoryTarget> ingestor = new PackIngestor<>(input, target)) {
                assertThat(ingestor.ingest()).isSameAs(target);
                assertThat(target.bytes.toByteArray()).containsExactly(wire);
                assertThat(target.offset).isEqualTo(12);
                assertThat(target.dataOffset).isEqualTo(13);
                assertThat(target.size).isEqualTo(3);
                assertThat(target.type).isEqualTo(GitObjectType.BLOB);
                MessageDigest hash = MessageDigest.getInstance("SHA-1");
                hash.update("blob 3\0".getBytes(StandardCharsets.US_ASCII));
                assertThat(target.id).isEqualTo(new ObjectId(hash.digest(new byte[]{1, 2, 3})));
            }
            assertThat(target.discarded).isFalse();
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @Test
    void checksumFailureDiscardsTheTargetAndLeavesTheInputOpen() throws Exception {
        byte[] wire = pack();
        wire[wire.length - 1] ^= 1;
        ByteBuffer source = ByteBuffer.allocate(wire.length + 1).put(wire).put((byte) 42).flip();
        MemoryTarget target = new MemoryTarget();
        try (BufferedByteInputV2 input = input(source)) {
            try (PackIngestor<MemoryTarget> ingestor = new PackIngestor<>(input, target)) {
                assertThatThrownBy(ingestor::ingest).isInstanceOf(IOException.class)
                        .hasMessage("Pack checksum mismatch");
            }
            assertThat(target.discarded).isTrue();
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

    private static final class MemoryTarget implements PackTarget {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long offset;
        private long dataOffset;
        private long size;
        private GitObjectType type;
        private ObjectId id;
        private boolean discarded;

        @Override
        public void append(ByteBuffer source) {
            byte[] data = new byte[source.remaining()];
            source.get(data);
            bytes.writeBytes(data);
        }

        @Override
        public boolean addEntry(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                                OptionalLong baseOffset, Optional<ObjectId> baseId) {
            this.offset = offset;
            this.dataOffset = dataOffset;
            this.size = inflatedSize;
            this.type = type;
            assertThat(baseOffset).isEmpty();
            assertThat(baseId).isEmpty();
            return true;
        }

        @Override
        public boolean addObject(long offset, ObjectId id, GitObjectType type, long size) {
            assertThat(offset).isEqualTo(this.offset);
            assertThat(type).isEqualTo(this.type);
            assertThat(size).isEqualTo(this.size);
            this.id = id;
            return true;
        }

        @Override
        public void discard() {
            discarded = true;
        }
    }
}
