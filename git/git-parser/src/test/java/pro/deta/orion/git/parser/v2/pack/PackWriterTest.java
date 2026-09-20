package pro.deta.orion.git.parser.v2.pack;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackWriterTest implements BufferedByteOutput {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int failureAt = Integer.MAX_VALUE;
    private boolean flushed;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixesCopiedAndNewObjectsAndResetsCompressionBetweenEntries(boolean direct) throws Exception {
        byte[] content = new byte[25000];
        new Random(5).nextBytes(content);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(compressed)) {
            zlib.write(content);
        }
        byte[] raw = compressed.toByteArray();
        try (PackWriter writer = new PackWriter(this, 3);
             BufferedByteInputV2 source = input(raw, direct);
             BufferedByteInputV2 plain = input(content, direct);
             BufferedByteInputV2 empty = input(new byte[0])) {
            writer.writeCompressed(GitObjectType.BLOB, content.length, Optional.empty(), source);
            writer.writeObject(GitObjectType.BLOB, content.length, plain);
            writer.writeObject(GitObjectType.BLOB, 0, empty);
            writer.finish();
        }
        byte[] bytes = output.toByteArray();
        ByteBuffer pack = ByteBuffer.wrap(bytes);
        assertThat(pack.getInt()).isEqualTo(0x5041434b);
        assertThat(pack.getInt()).isEqualTo(2);
        assertThat(pack.getInt()).isEqualTo(3);
        readBlob(pack, content);
        assertThat(Arrays.copyOfRange(bytes, pack.position() - raw.length, pack.position())).isEqualTo(raw);
        readBlob(pack, content);
        readBlob(pack, new byte[0]);
        assertThat(pack.remaining()).isEqualTo(20);
        byte[] checksum = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOf(bytes, pack.position()));
        assertThat(Arrays.copyOfRange(bytes, pack.position(), bytes.length)).isEqualTo(checksum);
        assertThat(flushed).isFalse();
    }

    @Test
    void closeDoesNotFinishAnIncompletePack() throws Exception {
        try (PackWriter writer = new PackWriter(this, 1)) {
            assertThatThrownBy(writer::finish).isInstanceOf(IOException.class).hasMessageContaining("count");
        }
        assertThat(output.size()).isEqualTo(12);
        assertThat(flushed).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void failedEntryCannotBeFollowedByASuccessTrailer(int declaredSize) throws Exception {
        try (PackWriter writer = new PackWriter(this, 1);
             BufferedByteInputV2 content = input(new byte[]{1, 2})) {
            assertThatThrownBy(() -> writer.writeObject(GitObjectType.BLOB, declaredSize, content))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(declaredSize == 3 ? "Truncated" : "exceeds");
            int written = output.size();
            assertThatThrownBy(writer::finish).isInstanceOf(IOException.class).hasMessageContaining("failed");
            assertThat(output.size()).isEqualTo(written);
        }
    }

    @Test
    void transportFailurePreventsFurtherWritesAndDoesNotFlush() throws Exception {
        try (PackWriter writer = new PackWriter(this, 1);
             BufferedByteInputV2 content = input(new byte[]{1, 2, 3})) {
            failureAt = output.size();
            assertThatThrownBy(() -> writer.writeObject(GitObjectType.BLOB, 3, content))
                    .isInstanceOf(IOException.class).hasMessage("transport failure");
            failureAt = Integer.MAX_VALUE;
            assertThatThrownBy(writer::finish).isInstanceOf(IOException.class).hasMessageContaining("failed");
        }
        assertThat(output.size()).isEqualTo(12);
        assertThat(flushed).isFalse();
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        if (output.size() >= failureAt) {
            throw new IOException("transport failure");
        }
        buffer.getBytes(buffer.readerIndex(), output, buffer.readableBytes());
    }

    @Override
    public void flush() {
        flushed = true;
    }

    private static BufferedByteInputV2 input(byte[] content) {
        return new BufferedByteInputV2(new ByteArrayInputStream(content));
    }

    private static BufferedByteInputV2 input(byte[] content, boolean direct) {
        if (!direct) {
            return input(content);
        }
        ByteBuffer bytes = ByteBuffer.allocateDirect(content.length + 4).putInt(42).put(content).flip();
        bytes.position(4);
        return new BufferedByteInputV2(new BufferedByteInputV2.Source() {
            private final ByteBuffer buffer = bytes.asReadOnlyBuffer();

            @Override
            public ByteBuffer read() {
                return buffer.hasRemaining() ? buffer : null;
            }

            @Override
            public void release() {}

            @Override
            public void close() {}
        });
    }

    private static void readBlob(ByteBuffer pack, byte[] expected) throws Exception {
        int part = Byte.toUnsignedInt(pack.get());
        assertThat((part >>> 4) & 7).isEqualTo(GitObjectType.BLOB.code());
        long size = part & 15;
        int shift = 4;
        while ((part & 128) != 0) {
            part = Byte.toUnsignedInt(pack.get());
            size |= (long) (part & 127) << shift;
            shift += 7;
        }
        assertThat(size).isEqualTo(expected.length);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(pack.array(), pack.position(), pack.remaining());
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                assertThat(count > 0 || inflater.finished()).isTrue();
                result.write(buffer, 0, count);
            }
            assertThat(result.toByteArray()).isEqualTo(expected);
            pack.position(pack.position() + (int) inflater.getBytesRead());
        } finally {
            inflater.end();
        }
    }
}
