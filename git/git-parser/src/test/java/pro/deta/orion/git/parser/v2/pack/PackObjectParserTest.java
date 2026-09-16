package pro.deta.orion.git.parser.v2.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.read.PresenceGitObjectRead;
import pro.deta.orion.git.parser.v2.read.RawGitObjectRead;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackObjectParserTest {
    @Test
    void hashesFullObjectsAndLeavesTheNextEntryInTheBorrowedInput() throws Exception {
        for (ObjectType type : new ObjectType[]{ObjectType.COMMIT, ObjectType.TREE,
                ObjectType.BLOB, ObjectType.TAG}) {
            byte[] content = "hello\n".getBytes(StandardCharsets.UTF_8);
            byte[] entry = join(header(type, content.length), compressed(content));
            byte[] next = join(header(ObjectType.BLOB, 0), compressed(new byte[0]));
            try (var source = input(join(entry, next, new byte[]{42})); var store = new ByteStore()) {
                var result = PackObjectParser.parseEntry(source, 12, store, new HashedGitObjectRead());
                var metadata = result.entry();
                assertThat(metadata.offset()).isEqualTo(12);
                assertThat(metadata.dataOffset()).isEqualTo(13);
                assertThat(metadata.inflatedSize()).isEqualTo(content.length);
                assertThat(metadata.type()).isEqualTo(type);
                assertThat(metadata.baseId()).isEmpty();
                assertThat(metadata.baseOffset()).isEmpty();
                MessageDigest hash = MessageDigest.getInstance("SHA-1");
                hash.update((type.name().toLowerCase(Locale.ROOT) + " " + content.length + "\0")
                        .getBytes(StandardCharsets.US_ASCII));
                assertThat(result.value()).isEqualTo(new ObjectId(hash.digest(content)));
                assertThat(store.bytes()).containsExactly(entry);
                var second = PackObjectParser.parseEntry(source, 12 + entry.length, store,
                        new HashedGitObjectRead());
                assertThat(second.value().toHex()).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
                assertThat(store.bytes()).containsExactly(join(entry, next));
                assertThat(source.readUnsignedByte()).isEqualTo(42);
                assertThat(store.isOpen()).isTrue();
            }
        }
    }

    @Test
    void exposesDeltaInstructionsAndTheirPhysicalBaseReferences() throws Exception {
        byte[] instructions = {3, 3, (byte) 0x90, 3};
        ObjectId baseId = new ObjectId("1234567890".repeat(4));
        for (ObjectType type : new ObjectType[]{ObjectType.OFS_DELTA, ObjectType.REF_DELTA}) {
            byte[] base = type == ObjectType.OFS_DELTA ? new byte[]{(byte) 0x81, 44} : baseId.toBytes();
            byte[] entry = join(header(type, instructions.length), base, compressed(instructions));
            try (var source = input(join(entry, new byte[]{42})); var store = new ByteStore()) {
                var result = PackObjectParser.parseEntry(source, 312, store,
                        new ContentGitObjectRead<>((physicalType, size, reference, content) -> {
                            assertThat(physicalType).isEqualTo(type);
                            assertThat(size).isEqualTo(instructions.length);
                            assertThat(reference).isEqualTo(type == ObjectType.REF_DELTA
                                    ? Optional.of(baseId) : Optional.empty());
                            return readAll(content);
                        }));
                assertThat(result.value()).containsExactly(instructions);
                assertThat(result.entry().dataOffset()).isEqualTo(313 + base.length);
                if (type == ObjectType.OFS_DELTA) {
                    assertThat(result.entry().baseOffset()).hasValue(12);
                    assertThat(result.entry().baseId()).isEmpty();
                } else {
                    assertThat(result.entry().baseId()).contains(baseId);
                    assertThat(result.entry().baseOffset()).isEmpty();
                }
                assertThat(source.readUnsignedByte()).isEqualTo(42);
                assertThat(store.bytes()).containsExactly(entry);
            }
        }
    }

    @Test
    void copiesOnlyCompressedPayloadWithPartialSinkWritesAndLargeContent() throws Exception {
        byte[] content = new byte[200_000];
        new Random(73).nextBytes(content);
        byte[] zlib = compressed(content);
        byte[] entry = join(header(ObjectType.BLOB, content.length), zlib);
        try (var source = input(join(entry, new byte[]{42})); var store = new ByteStore()) {
            var result = PackObjectParser.parseEntry(source, 12, store,
                    new RawGitObjectRead<>((type, size, baseId, raw) -> readAll(raw)));
            assertThat(result.value()).containsExactly(zlib);
            assertThat(result.entry().inflatedSize()).isEqualTo(content.length);
            assertThat(result.entry().dataOffset())
                    .isEqualTo(12 + header(ObjectType.BLOB, content.length).length);
            assertThat(store.bytes()).containsExactly(entry);
            assertThat(source.readUnsignedByte()).isEqualTo(42);
        }
    }

    @Test
    void hashesLargeContentAcrossBothZlibReaders() throws Exception {
        byte[] content = new byte[200_000];
        new Random(73).nextBytes(content);
        byte[] entry = join(header(ObjectType.BLOB, content.length), compressed(content));
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        hash.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        try (var source = input(join(entry, new byte[]{42})); var store = new ByteStore()) {
            var result = PackObjectParser.parseEntry(source, 12, store, new HashedGitObjectRead());
            assertThat(result.value()).isEqualTo(new ObjectId(hash.digest(content)));
            assertThat(store.bytes()).containsExactly(entry);
            assertThat(source.readUnsignedByte()).isEqualTo(42);
        }
    }

    @Test
    void drainsPayloadWhenTheProcessorReturnsWithoutReading() throws Exception {
        byte[] content = new byte[100_000];
        byte[] entry = join(header(ObjectType.BLOB, content.length), compressed(content));
        try (var source = input(join(entry, new byte[]{42})); var store = new ByteStore()) {
            assertThat(PackObjectParser.parseEntry(source, 12, store, new PresenceGitObjectRead()).value())
                    .isTrue();
            assertThat(store.bytes()).containsExactly(entry);
            assertThat(source.readUnsignedByte()).isEqualTo(42);
        }
    }

    @Test
    void rejectsEveryTruncatedPrefixOfAnEntry() throws Exception {
        byte[] entry = join(header(ObjectType.REF_DELTA, 3), new byte[20], compressed(new byte[]{1, 2, 3}));
        for (int length = 0; length < entry.length; length++) {
            try (var source = input(Arrays.copyOf(entry, length)); var store = new ByteStore()) {
                assertThatThrownBy(() -> PackObjectParser.parseEntry(source, 12, store,
                        new PresenceGitObjectRead())).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rejectsInvalidTypesSizesAndOffsetReferences() throws Exception {
        byte[] sizeOverflow = {(byte) 0xb0, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 8};
        byte[] offsetOverflow = {0x60, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0};
        for (byte[] prefix : new byte[][]{{0}, {0x50}, {0x60, 0}, {0x60, 1},
                {0x60, 13}, sizeOverflow, offsetOverflow}) {
            try (var source = input(join(prefix, compressed(new byte[0]))); var store = new ByteStore()) {
                assertThatThrownBy(() -> PackObjectParser.parseEntry(source, 12, store,
                        new PresenceGitObjectRead())).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rejectsCorruptStreamsWrongLengthsAndPresetDictionariesEvenWithoutAReader() throws Exception {
        byte[] valid = compressed(new byte[]{1, 2, 3});
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        byte[] dictionaryStream;
        Deflater deflater = new Deflater();
        try {
            deflater.setDictionary(new byte[]{1, 2, 3});
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var output = new DeflaterOutputStream(bytes, deflater)) {
                output.write(new byte[]{1, 2, 3});
            }
            dictionaryStream = bytes.toByteArray();
        } finally {
            deflater.end();
        }
        for (byte[] entry : new byte[][]{join(header(ObjectType.BLOB, 2), valid),
                join(header(ObjectType.BLOB, 4), valid), join(header(ObjectType.BLOB, 3), corrupt),
                join(header(ObjectType.BLOB, 3), dictionaryStream)}) {
            try (var source = input(entry); var store = new ByteStore()) {
                assertThatThrownBy(() -> PackObjectParser.parseEntry(source, 12, store,
                        new PresenceGitObjectRead())).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void closesUnreturnedResultsAndPreservesTheValidationFailure() throws Exception {
        byte[] entry = join(header(ObjectType.BLOB, 4), compressed(new byte[]{1, 2, 3}));
        boolean[] closed = {false};
        AutoCloseable resource = () -> {
            closed[0] = true;
            throw new IOException("cleanup failure");
        };
        try (var source = input(entry); var store = new ByteStore()) {
            assertThatThrownBy(() -> PackObjectParser.parseEntry(source, 12, store,
                    (type, size, baseId, raw) -> resource)).isInstanceOf(IOException.class)
                    .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
            assertThat(closed[0]).isTrue();
        }
    }

    @Test
    void reportsSinkFailureWithoutClosingBorrowedResources() throws Exception {
        byte[] entry = join(header(ObjectType.BLOB, 0), compressed(new byte[0]));
        try (var source = input(entry); var store = new ByteStore()) {
            store.failWrites = true;
            assertThatThrownBy(() -> PackObjectParser.parseEntry(source, 12, store,
                    new PresenceGitObjectRead())).isInstanceOf(IOException.class).hasMessage("sink failure");
            assertThat(store.isOpen()).isTrue();
        }
    }

    @Test
    void closesAResultIfRetainingTheFinalBytesFails() throws Exception {
        byte[] entry = join(header(ObjectType.BLOB, 0), compressed(new byte[0]));
        int[] closed = {0};
        AutoCloseable resource = () -> closed[0]++;
        try (var source = input(entry); var store = new ByteStore()) {
            assertThatThrownBy(() -> PackObjectParser.parseEntry(source, 12, store, (type, size, baseId, raw) -> {
                readAll(raw);
                store.failWrites = true;
                return resource;
            })).isInstanceOf(IOException.class).hasMessage("sink failure");
            assertThat(closed[0]).isEqualTo(1);
        }
    }

    @Test
    void rejectsAbsoluteOffsetOverflow() throws Exception {
        byte[] entry = join(header(ObjectType.BLOB, 0), compressed(new byte[0]));
        try (var source = input(entry); var store = new ByteStore()) {
            assertThatThrownBy(() -> PackObjectParser.parseEntry(source, Long.MAX_VALUE - 1, store,
                    new PresenceGitObjectRead()))
                    .isInstanceOf(IOException.class).hasMessageContaining("offset");
        }
    }

    private static byte[] header(ObjectType type, long size) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int first = type.code() << 4 | (int) (size & 15);
        size >>>= 4;
        output.write(first | (size == 0 ? 0 : 128));
        while (size != 0) {
            int part = (int) (size & 127);
            size >>>= 7;
            output.write(part | (size == 0 ? 0 : 128));
        }
        return output.toByteArray();
    }

    private static byte[] compressed(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var compressor = new DeflaterOutputStream(output)) {
            compressor.write(content);
        }
        return output.toByteArray();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private static InputStreamBufferedByteInput input(byte[] content) {
        return new InputStreamBufferedByteInput(new ByteArrayInputStream(content) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 3));
            }
        });
    }

    private static byte[] readAll(BufferedByteInput source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuf buffer = Unpooled.buffer(4096);
        try {
            while (source.readInto(buffer, buffer.writableBytes()) != 0) {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                output.writeBytes(bytes);
                buffer.clear();
            }
            return output.toByteArray();
        } finally {
            buffer.release();
        }
    }

    private static final class ByteStore implements PackByteStore {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean open = true;
        private boolean failWrites;

        byte[] bytes() {
            return output.toByteArray();
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (failWrites) {
                throw new IOException("sink failure");
            }
            int count = Math.min(7, source.remaining());
            for (int i = 0; i < count; i++) {
                output.write(source.get());
            }
            return count;
        }

        @Override
        public int read(long offset, ByteBuffer destination) {
            byte[] bytes = bytes();
            int count = (int) Math.min(destination.remaining(), bytes.length - offset);
            if (count <= 0) {
                return -1;
            }
            destination.put(bytes, (int) offset, count);
            return count;
        }

        @Override
        public void force() {
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
