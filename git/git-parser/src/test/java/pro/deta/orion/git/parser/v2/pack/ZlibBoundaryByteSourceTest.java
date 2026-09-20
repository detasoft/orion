package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.compressed;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.join;

class ZlibBoundaryByteSourceTest implements BufferedByteInputV2.Source {
    private byte[] bytes;
    private ByteBuffer chunk;
    private int position;
    private int releases;
    private boolean closed;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesConsecutiveStreamsAcrossReusedBuffers(boolean virtualThread) throws Exception {
        if (virtualThread) {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> {
                    readConsecutiveStreams();
                    return null;
                }).get(30, TimeUnit.SECONDS);
            }
        } else {
            readConsecutiveStreams();
        }
    }

    private void readConsecutiveStreams() throws Exception {
        byte[] random = new byte[30_000];
        new Random(42).nextBytes(random);
        byte[] expanding = new byte[65_536];
        byte[] first = compressed(random);
        byte[] second = compressed(expanding);
        byte[] empty = compressed(new byte[0]);
        for (boolean direct : new boolean[]{false, true}) {
            for (int capacity : new int[]{1, 17, 8192, first.length + second.length + empty.length + 1}) {
                try (BufferedByteInputV2 raw = input(join(first, second, empty, new byte[]{42}), capacity, direct)) {
                    assertStream(raw, first, random.length);
                    assertStream(raw, second, expanding.length);
                    assertStream(raw, empty, 0);
                    assertThat(closed).isFalse();
                    assertThat(raw.readUnsignedByte()).isEqualTo(42);
                    assertThat(raw.buffer()).isNull();
                }
                assertThat(closed).isTrue();
                assertThat(releases).isPositive();
            }
        }
    }

    private static void assertStream(BufferedByteInputV2 raw, byte[] expected, long size) throws IOException {
        try (BufferedByteInputV2 stream = new BufferedByteInputV2(new ZlibBoundaryByteSource(raw, size))) {
            assertThat(stream.readUnsignedByte()).isEqualTo(Byte.toUnsignedInt(expected[0]));
            assertThat(stream.readBytes(expected.length - 1)).containsExactly(
                    Arrays.copyOfRange(expected, 1, expected.length));
            assertThat(stream.buffer()).isNull();
        }
    }

    @Test
    void returnsAReadOnlyViewWithoutCopyingOrReleasingTheUnderlyingBuffer() throws Exception {
        byte[] compressed = compressed(new byte[]{1, 2, 3});
        try (BufferedByteInputV2 raw = input(join(compressed, new byte[]{42}), 100, true);
             BufferedByteInputV2 stream = new BufferedByteInputV2(new ZlibBoundaryByteSource(raw, 3))) {
            ByteBuffer view = stream.buffer();
            assertThat(view.isDirect()).isTrue();
            assertThat(view.isReadOnly()).isTrue();
            assertThat(view.remaining()).isEqualTo(compressed.length);
            assertThat(releases).isZero();
            chunk.put(0, (byte) 43);
            assertThat(stream.readUnsignedByte()).isEqualTo(43);
            view.position(view.limit());
            assertThat(stream.buffer()).isNull();
            assertThat(releases).isZero();
            assertThat(raw.readUnsignedByte()).isEqualTo(42);
        }
    }

    @Test
    void rejectsCorruptionTruncationSizeMismatchAndDictionary() throws Exception {
        byte[] content = new byte[32_000];
        new Random(43).nextBytes(content);
        byte[] valid = compressed(content);
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        byte[] dictionary;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        try {
            deflater.setDictionary(new byte[]{1, 2, 3});
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(output, deflater)) {
                compressed.write(content);
            }
            dictionary = output.toByteArray();
        } finally {
            deflater.end();
        }
        for (int capacity : new int[]{1, 8192}) {
            for (byte[] encoded : new byte[][]{corrupt, Arrays.copyOf(valid, valid.length - 1), dictionary}) {
                assertRejected(encoded, content.length, capacity);
            }
            assertRejected(valid, content.length - 1, capacity);
            assertRejected(valid, content.length + 1, capacity);
        }
    }

    private void assertRejected(byte[] encoded, long size, int capacity) throws IOException {
        try (BufferedByteInputV2 raw = input(encoded, capacity, true);
             BufferedByteInputV2 stream = new BufferedByteInputV2(new ZlibBoundaryByteSource(raw, size))) {
            assertThatThrownBy(() -> {
                ByteBuffer part;
                while ((part = stream.buffer()) != null) {
                    part.position(part.limit());
                }
            }).isInstanceOf(IOException.class);
            assertThat(closed).isFalse();
        }
    }

    private BufferedByteInputV2 input(byte[] content, int capacity, boolean direct) {
        bytes = content;
        chunk = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        position = 0;
        releases = 0;
        closed = false;
        return new BufferedByteInputV2(this);
    }

    @Override
    public ByteBuffer read() {
        if (position == bytes.length) {
            return null;
        }
        int count = Math.min(chunk.capacity(), bytes.length - position);
        chunk.clear().put(bytes, position, count).flip();
        position += count;
        return chunk.asReadOnlyBuffer();
    }

    @Override
    public void release() {
        releases++;
        chunk.clear();
        while (chunk.hasRemaining()) {
            chunk.put((byte) -1);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
