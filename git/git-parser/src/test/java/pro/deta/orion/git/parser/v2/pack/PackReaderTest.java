package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PackReaderTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192, 262144})
    void emitsExactBytesAndFinalIdsWithReusedInputBuffers(int chunkSize) throws Exception {
        byte[] content = new byte[100_000];
        new Random(17).nextBytes(content);
        byte[] repeated = new byte[1_000_000];
        Arrays.fill(repeated, (byte) 8);
        byte[][] entries = {blob(content), blob(repeated), blob(new byte[0])};
        byte[] wire = pack(entries);
        ReusedSource source = new ReusedSource(join(wire, new byte[]{42}), chunkSize);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source); PackReader reader = new PackReader(input)) {
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            List<ObjectId> ids = new ArrayList<>();
            long offset = PackHeader.SIZE;
            boolean ended = false;
            while (!ended) {
                switch (reader.next()) {
                    case PackReadStep.Bytes bytes -> copy(bytes, copied);
                    case PackReadStep.EntryEnd end -> {
                        assertThat(end.metadata().offset()).isEqualTo(offset);
                        offset += entries[ids.size()].length;
                        assertThat(copied.size()).isEqualTo(offset);
                        ids.add(end.objectId().orElseThrow());
                    }
                    case PackReadStep.End end -> {
                        assertThat(end.id()).isEqualTo(new PackChecksum(Arrays.copyOfRange(wire, wire.length - 20,
                                wire.length)));
                        ended = true;
                    }
                }
            }
            assertThat(copied.toByteArray()).containsExactly(wire);
            assertThat(ids).containsExactly(objectId(GitObjectType.BLOB, content),
                    objectId(GitObjectType.BLOB, repeated), objectId(GitObjectType.BLOB, new byte[0]));
            assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
            assertThat(source.closed).isFalse();
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
        assertThat(source.closed).isTrue();
    }

    @Test
    void deltasHavePhysicalBasesButNoFinalId() throws Exception {
        byte[] base = blob(new byte[]{1, 2, 3});
        ObjectId baseId = objectId(GitObjectType.BLOB, new byte[]{1, 2, 3});
        byte[] instructions = {3, 3, (byte) 0x90, 3};
        byte[] ofs = join(new byte[]{0x64, (byte) base.length}, compressed(instructions));
        byte[] wire = pack(base, ofs, delta(baseId, instructions));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ReusedSource(wire, 7));
             PackReader reader = new PackReader(input)) {
            List<PackReadStep.EntryEnd> entries = new ArrayList<>();
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            while (true) {
                PackReadStep step = reader.next();
                if (step instanceof PackReadStep.Bytes bytes) {
                    copy(bytes, copied);
                } else if (step instanceof PackReadStep.EntryEnd end) {
                    entries.add(end);
                } else {
                    break;
                }
            }
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).objectId()).contains(baseId);
            assertThat(entries.get(1).objectId()).isEmpty();
            assertThat(entries.get(1).metadata().baseOffset()).hasValue(12);
            assertThat(entries.get(2).objectId()).isEmpty();
            assertThat(entries.get(2).metadata().baseId()).contains(baseId);
            assertThat(copied.toByteArray()).containsExactly(wire);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void preservesUnsortedTreeBytesAndObjectIds(int chunkSize) throws Exception {
        byte[] content = "file content".getBytes(StandardCharsets.UTF_8);
        ObjectId blobId = objectId(GitObjectType.BLOB, content);
        byte[] tree = join("100644 \uD800\uDC00\0".getBytes(StandardCharsets.UTF_8), blobId.toBytes(),
                "100644 \uE000\0".getBytes(StandardCharsets.UTF_8), blobId.toBytes());
        byte[] wire = pack(blob(content), entry(GitObjectType.TREE, tree));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ReusedSource(wire, chunkSize));
             PackReader reader = new PackReader(input)) {
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            List<ObjectId> ids = new ArrayList<>();
            PackReadStep step;
            while (!((step = reader.next()) instanceof PackReadStep.End)) {
                if (step instanceof PackReadStep.Bytes bytes) {
                    copy(bytes, copied);
                } else if (step instanceof PackReadStep.EntryEnd end) {
                    ids.add(end.objectId().orElseThrow());
                }
            }

            assertThat(copied.toByteArray()).containsExactly(wire);
            assertThat(ids).containsExactly(blobId, objectId(GitObjectType.TREE, tree));
        }
    }

    @Test
    void completedObjectDoesNotHideACorruptTrailerAndFailureTerminatesReader() throws Exception {
        byte[] wire = pack(blob(new byte[]{1}));
        wire[wire.length - 1] ^= 1;
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ReusedSource(join(wire, new byte[]{42}), 3));
             PackReader reader = new PackReader(input)) {
            PackReadStep step;
            do {
                step = reader.next();
            } while (!(step instanceof PackReadStep.EntryEnd));
            assertThat(((PackReadStep.EntryEnd) step).objectId()).contains(objectId(GitObjectType.BLOB,
                    new byte[]{1}));
            assertThatThrownBy(reader::next).isInstanceOf(IOException.class).hasMessage("Pack checksum mismatch");
            assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void emptyPackEmitsOnlyBytesThenEnd(int version) throws Exception {
        byte[] wire = pack(version);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ReusedSource(join(wire, new byte[]{42}), 1));
             PackReader reader = new PackReader(input)) {
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            PackReadStep step;
            while (!((step = reader.next()) instanceof PackReadStep.End)) {
                assertThat(step).isInstanceOf(PackReadStep.Bytes.class);
                copy((PackReadStep.Bytes) step, copied);
            }
            assertThat(copied.toByteArray()).containsExactly(wire);
            PackReadStep.End end = (PackReadStep.End) step;
            assertThat(end.id()).isEqualTo(new PackChecksum(Arrays.copyOfRange(wire, PackHeader.SIZE, wire.length)));
            assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 12})
    void checksumIncludesPackAndEntryHeaders(int changedOffset) throws Exception {
        byte[] wire = pack(blob(new byte[]{1}));
        wire[changedOffset] ^= changedOffset == 7 ? 1 : 0x10;
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ReusedSource(wire, 7));
             PackReader reader = new PackReader(input)) {
            assertThatThrownBy(() -> {
                while (!(reader.next() instanceof PackReadStep.End)) {
                    // Reach the checksum check after reading the otherwise valid entry.
                }
            }).isInstanceOf(IOException.class).hasMessage("Pack checksum mismatch");
            assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void truncatedInputTerminatesReaderAtEveryBoundary() throws Exception {
        byte[] wire = pack(blob(new byte[]{1}), delta(new ObjectId(new byte[20]), new byte[]{1, 1, 1, 2}));
        for (int length = 0; length < wire.length; length++) {
            try (BufferedByteInputV2 input = new BufferedByteInputV2(
                    new ReusedSource(Arrays.copyOf(wire, length), 3));
                 PackReader reader = new PackReader(input)) {
                assertThatThrownBy(() -> {
                    while (!(reader.next() instanceof PackReadStep.End)) {
                        // Every strict prefix must fail before End.
                    }
                }).isInstanceOf(IOException.class);
                assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void closingEarlyLeavesInputWithCaller() throws Exception {
        ReusedSource source = new ReusedSource(pack(), 3);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            PackReader reader = new PackReader(input);
            reader.close();
            reader.close();
            assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
            assertThat(source.closed).isFalse();
            assertThat(input.readUnsignedByte()).isEqualTo('P');
        }
    }

    private static void copy(PackReadStep.Bytes bytes, ByteArrayOutputStream copied) {
        assertThat(bytes.data().hasRemaining()).isTrue();
        byte[] chunk = new byte[bytes.data().remaining()];
        bytes.data().get(chunk);
        copied.writeBytes(chunk);
    }

    private static final class ReusedSource implements BufferedByteInputV2.Source {
        private final ByteBuffer source;
        private final ByteBuffer chunk;
        private boolean closed;

        private ReusedSource(byte[] bytes, int chunkSize) {
            source = ByteBuffer.wrap(bytes);
            chunk = ByteBuffer.allocate(chunkSize);
        }

        @Override
        public ByteBuffer read() {
            if (!source.hasRemaining()) {
                return null;
            }
            chunk.clear();
            int length = Math.min(chunk.remaining(), source.remaining());
            chunk.put(source.slice(source.position(), length));
            source.position(source.position() + length);
            return chunk.flip();
        }

        @Override
        public void release() {
            Arrays.fill(chunk.array(), (byte) 0);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
