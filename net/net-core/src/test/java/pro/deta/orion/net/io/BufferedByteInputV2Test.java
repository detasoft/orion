package pro.deta.orion.net.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BufferedByteInputV2Test {
    @Test
    void streamSourceReusesItsBufferAndClosesItsInputOnce() throws Exception {
        byte[] data = new byte[20000];
        new Random(91).nextBytes(data);
        int[] closes = {0};
        ByteArrayInputStream stream = new ByteArrayInputStream(data) {
            @Override
            public void close() {
                closes[0]++;
            }
        };
        BufferedByteInputV2 input = new BufferedByteInputV2(stream);
        try (input) {
            ByteBuffer first = input.buffer();
            int count = first.remaining();
            assertThat(input.readBytes(count)).containsExactly(java.util.Arrays.copyOf(data, count));
            assertThat(input.buffer()).isSameAs(first);
            assertThat(input.newInputStream().readAllBytes())
                    .containsExactly(java.util.Arrays.copyOfRange(data, count, data.length));
            assertThat(input.buffer()).isNull();
        }
        input.close();
        assertThat(closes[0]).isEqualTo(1);
    }

    @Test
    void streamSourceHandlesFragmentedReadsOnVirtualThreadsAndPropagatesFailures() throws Exception {
        onVirtualThread(() -> {
            byte[] bytes = {1, 2, 3, 4, 42};
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes) {
                @Override
                public synchronized int read(byte[] target, int offset, int length) {
                    return super.read(target, offset, Math.min(length, 2));
                }
            })) {
                assertThat(input.readInt()).isEqualTo(0x01020304);
                assertThat(input.newInputStream().read()).isEqualTo(42);
                assertThat(input.newInputStream().read()).isEqualTo(-1);
            }
            IOException failure = new IOException("transport failed");
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw failure;
                }
            })) {
                assertThatThrownBy(input::buffer).isSameAs(failure);
            }
            return null;
        });
    }

    @Test
    void exactReadsWorkOnVirtualThreads() throws Exception {
        onVirtualThread(() -> {
            readsIntInNetworkOrderFromTheCurrentPosition();
            for (int split = 1; split <= 4; split++) {
                readsIntAcrossChunksWithoutConsumingTheFollowingByte(split);
            }
            for (int length = 0; length < 4; length++) {
                intReadReportsTruncation(length);
            }
            readsUnsignedBytesAcrossChunksAndReportsEof();
            readsExactlyTheRequestedBytesAcrossChunksAndPreservesTheRemainder();
            emptyAndNegativeExactReadsDoNotAcquireChunks();
            exactReadReportsTruncationInsteadOfReturningPartialContent();
            exactReadsRejectAClosedInput();
            return null;
        });
    }

    @Test
    void streamViewsAndBufferOwnershipWorkOnVirtualThreads() throws Exception {
        onVirtualThread(() -> {
            readsTransportBuffersDirectlyAndReleasesThemBeforeGettingTheNext();
            releasesEmptyChunksAndContinuesToData();
            streamViewsShareTheCurrentPositionWithRawReads();
            streamSkipCrossesChunksAndViewCloseDoesNotCloseTheSource();
            emptyAndInvalidStreamReadsDoNotAcquireChunks();
            releasesTheConsumedChunkBeforePropagatingReadFailure();
            closeReleasesAnUnreadChunkAndInvalidatesExistingStreamViews();
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void rawAndZlibReadsPreserveEntryBoundariesOnVirtualThreads(int capacity) throws Exception {
        onVirtualThread(() -> {
            alternatesRawAndZlibReadsWithoutLosingEntryBoundaries(capacity);
            return null;
        });
    }

    @Test
    void readsIntInNetworkOrderFromTheCurrentPosition() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(6)
                .put(new byte[]{9, (byte) 128, 2, 3, 4, 42}).flip();
        buffer.position(1);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new RecordingSource(buffer))) {
            assertThat(input.readInt()).isEqualTo(0x80020304);
            assertThat(buffer.order()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void readsIntAcrossChunksWithoutConsumingTheFollowingByte(int split) throws Exception {
        byte[] bytes = {(byte) 255, (byte) 128, 3, 4, 42};
        RecordingSource source = new RecordingSource(
                ByteBuffer.wrap(bytes, 0, split), ByteBuffer.allocate(0),
                ByteBuffer.wrap(bytes, split, bytes.length - split));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.readInt()).isEqualTo(0xff800304);
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void intReadReportsTruncation(int length) throws Exception {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(
                new RecordingSource(ByteBuffer.allocate(length)))) {
            assertThatThrownBy(input::readInt).isInstanceOf(EOFException.class);
        }
    }

    @Test
    void readsUnsignedBytesAcrossChunksAndReportsEof() throws Exception {
        RecordingSource source = new RecordingSource(
                ByteBuffer.wrap(new byte[]{0, (byte) 128}), ByteBuffer.wrap(new byte[]{(byte) 255}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.readUnsignedByte()).isZero();
            assertThat(input.readUnsignedByte()).isEqualTo(128);
            assertThat(input.readUnsignedByte()).isEqualTo(255);
            assertThatThrownBy(input::readUnsignedByte).isInstanceOf(EOFException.class);
        }
    }

    @Test
    void readsExactlyTheRequestedBytesAcrossChunksAndPreservesTheRemainder() throws Exception {
        ByteBuffer second = ByteBuffer.allocateDirect(3).put(new byte[]{3, 4, 5}).flip();
        RecordingSource source = new RecordingSource(ByteBuffer.wrap(new byte[]{1, 2}), second);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.readUnsignedByte()).isEqualTo(1);
            byte[] bytes = input.readBytes(3);
            assertThat(bytes).containsExactly(2, 3, 4);
            assertThat(input.buffer()).isSameAs(second);
            assertThat(input.newInputStream().read()).isEqualTo(5);
            second.put(0, (byte) 99);
            assertThat(bytes).containsExactly(2, 3, 4);
            assertThat(input.buffer()).isNull();
        }
    }

    @Test
    void emptyAndNegativeExactReadsDoNotAcquireChunks() throws Exception {
        RecordingSource source = new RecordingSource(ByteBuffer.wrap(new byte[]{42}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.readBytes(0)).isEmpty();
            assertThatThrownBy(() -> input.readBytes(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThat(source.reads).isZero();
            assertThat(input.readBytes(1)).containsExactly(42);
            assertThat(input.buffer()).isNull();
            assertThat(input.readBytes(0)).isEmpty();
        }
    }

    @Test
    void exactReadReportsTruncationInsteadOfReturningPartialContent() throws Exception {
        RecordingSource source = new RecordingSource(ByteBuffer.wrap(new byte[]{1, 2}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThatThrownBy(() -> input.readBytes(3)).isInstanceOf(EOFException.class);
            assertThat(input.buffer()).isNull();
            assertThat(source.releases).isEqualTo(1);
        }
    }

    @Test
    void exactReadsRejectAClosedInput() throws Exception {
        BufferedByteInputV2 input = new BufferedByteInputV2(new RecordingSource());
        input.close();
        assertThatThrownBy(input::readUnsignedByte).isInstanceOf(IOException.class);
        assertThatThrownBy(input::readInt).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> input.readBytes(1)).isInstanceOf(IOException.class);
    }

    @Test
    void readsTransportBuffersDirectlyAndReleasesThemBeforeGettingTheNext() throws Exception {
        ByteBuffer first = ByteBuffer.wrap(new byte[]{0, 1, 2, 9});
        first.position(1).limit(3);
        ByteBuffer second = ByteBuffer.allocateDirect(1).put((byte) 3).flip();
        RecordingSource source = new RecordingSource(first, second);
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.buffer()).isSameAs(first);
            assertThat(input.buffer().get()).isEqualTo((byte) 1);
            assertThat(input.buffer()).isSameAs(first);
            assertThat(source.reads).isEqualTo(1);
            assertThat(source.releases).isZero();
            assertThat(input.buffer().get()).isEqualTo((byte) 2);
            assertThat(input.buffer()).isSameAs(second);
            assertThat(source.releases).isEqualTo(1);
            assertThat(second.get()).isEqualTo((byte) 3);
            assertThat(input.buffer()).isNull();
            assertThat(input.buffer()).isNull();
            assertThat(source.reads).isEqualTo(3);
            assertThat(source.releases).isEqualTo(2);
        }
        assertThat(source.closes).isEqualTo(1);
    }

    @Test
    void releasesEmptyChunksAndContinuesToData() throws Exception {
        RecordingSource source = new RecordingSource(ByteBuffer.allocate(0), ByteBuffer.wrap(new byte[]{42}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.buffer().get()).isEqualTo((byte) 42);
            assertThat(source.releases).isEqualTo(1);
        }
        assertThat(source.releases).isEqualTo(2);
    }

    @Test
    void streamViewsShareTheCurrentPositionWithRawReads() throws Exception {
        RecordingSource source = new RecordingSource(
                ByteBuffer.wrap(new byte[]{0, (byte) 255, 2}), ByteBuffer.wrap(new byte[]{3, 4, 5}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.buffer().get()).isZero();
            InputStream stream = input.newInputStream();
            assertThat(stream.available()).isEqualTo(2);
            assertThat(stream.read()).isEqualTo(255);
            assertThat(input.buffer().get()).isEqualTo((byte) 2);
            assertThat(stream.available()).isZero();
            assertThat(source.reads).isEqualTo(1);
            byte[] target = new byte[5];
            assertThat(stream.read(target, 1, 4)).isEqualTo(3);
            assertThat(target).containsExactly(0, 3, 4, 5, 0);
            assertThat(stream.read()).isEqualTo(-1);
            assertThat(stream.read(target, 0, 1)).isEqualTo(-1);
            assertThat(stream.read(target, 0, 0)).isZero();
            assertThat(stream.markSupported()).isFalse();
        }
    }

    @Test
    void streamSkipCrossesChunksAndViewCloseDoesNotCloseTheSource() throws Exception {
        RecordingSource source = new RecordingSource(
                ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            InputStream stream = input.newInputStream();
            assertThat(stream.skip(3)).isEqualTo(3);
            stream.close();
            assertThat(source.closes).isZero();
            assertThat(input.newInputStream().read()).isEqualTo(4);
            assertThat(input.newInputStream().skip(10)).isZero();
        }
    }

    @Test
    void emptyAndInvalidStreamReadsDoNotAcquireChunks() throws Exception {
        RecordingSource source = new RecordingSource(ByteBuffer.wrap(new byte[]{1}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            InputStream stream = input.newInputStream();
            assertThat(stream.available()).isZero();
            assertThat(stream.read(new byte[0])).isZero();
            assertThat(stream.skip(-1)).isZero();
            assertThatThrownBy(() -> stream.read(new byte[1], 0, 2))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> stream.read(null, 0, 0)).isInstanceOf(NullPointerException.class);
            assertThat(source.reads).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void alternatesRawAndZlibReadsWithoutLosingEntryBoundaries(int capacity) throws Exception {
        byte[] content = new byte[20000];
        new Random(42).nextBytes(content);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int entry = 0; entry < 2; entry++) {
            wire.write(entry);
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(wire)) {
                compressed.write(content);
            }
        }
        wire.write(99);
        byte[] bytes = wire.toByteArray();
        RecordingSource source = new RecordingSource() {
            private final ByteBuffer chunk = ByteBuffer.allocateDirect(capacity);
            private int offset;

            @Override
            public ByteBuffer read() {
                assertThat(releases).isEqualTo(reads);
                if (offset == bytes.length) {
                    return null;
                }
                reads++;
                int length = Math.min(capacity, bytes.length - offset);
                chunk.clear().put(bytes, offset, length).flip();
                offset += length;
                return chunk;
            }
        };
        Inflater inflater = new Inflater();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            byte[] output = new byte[113];
            for (int entry = 0; entry < 2; entry++) {
                assertThat(input.buffer().get()).isEqualTo((byte) entry);
                ByteArrayOutputStream restored = new ByteArrayOutputStream();
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        ByteBuffer buffer = input.buffer();
                        assertThat(buffer).isNotNull();
                        inflater.setInput(buffer);
                    }
                    int count = inflater.inflate(output);
                    restored.write(output, 0, count);
                    assertThat(count > 0 || inflater.needsInput() || inflater.finished()).isTrue();
                }
                assertThat(restored.toByteArray()).isEqualTo(content);
                inflater.reset();
            }
            assertThat(input.buffer().get()).isEqualTo((byte) 99);
            assertThat(input.buffer()).isNull();
        } finally {
            inflater.end();
        }
        assertThat(source.releases).isEqualTo((bytes.length + capacity - 1) / capacity);
    }

    @Test
    @Timeout(10)
    void waitsForSocketBytesOnAVirtualThread() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel writer = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel reader = listener.accept()) {
                CountDownLatch reading = new CountDownLatch(1);
                ByteBuffer transportBuffer = ByteBuffer.allocateDirect(8);
                RecordingSource source = new RecordingSource() {
                    @Override
                    public ByteBuffer read() throws IOException {
                        reading.countDown();
                        transportBuffer.clear();
                        return reader.read(transportBuffer) < 0 ? null : transportBuffer.flip();
                    }
                };
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
                    Future<Integer> received = executor.submit(() -> {
                        assertThat(Thread.currentThread().isVirtual()).isTrue();
                        return input.newInputStream().read();
                    });
                    assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> received.get(50, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    writer.write(ByteBuffer.wrap(new byte[]{(byte) 255}));
                    assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(255);
                    writer.shutdownOutput();
                    assertThat(input.buffer()).isNull();
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }

    @Test
    void releasesTheConsumedChunkBeforePropagatingReadFailure() throws Exception {
        IOException failure = new IOException("read failed");
        RecordingSource source = new RecordingSource(ByteBuffer.wrap(new byte[]{42})) {
            @Override
            public ByteBuffer read() throws IOException {
                if (reads > 0) {
                    throw failure;
                }
                return super.read();
            }
        };
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            assertThat(input.buffer().get()).isEqualTo((byte) 42);
            assertThatThrownBy(input::buffer).isSameAs(failure);
            assertThat(source.releases).isEqualTo(1);
        }
        assertThat(source.releases).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
    }

    @Test
    void closeReleasesAnUnreadChunkAndInvalidatesExistingStreamViews() throws Exception {
        RecordingSource source = new RecordingSource(ByteBuffer.wrap(new byte[]{1, 2}));
        BufferedByteInputV2 input = new BufferedByteInputV2(source);
        InputStream stream = input.newInputStream();
        assertThat(stream.read()).isEqualTo(1);
        input.close();
        input.close();
        assertThat(source.releases).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
        assertThatThrownBy(input::buffer).isInstanceOf(IOException.class);
        assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
    }

    private static void onVirtualThread(Callable<Void> scenario) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Void> completed = executor.submit(() -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                return scenario.call();
            });
            completed.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static class RecordingSource implements BufferedByteInputV2.Source {
        private final ByteBuffer[] chunks;
        int reads;
        int releases;
        int closes;

        RecordingSource(ByteBuffer... chunks) {
            this.chunks = chunks;
        }

        @Override
        public ByteBuffer read() throws IOException {
            assertThat(releases).isEqualTo(Math.min(reads, chunks.length));
            return reads++ < chunks.length ? chunks[reads - 1] : null;
        }

        @Override
        public void release() {
            releases++;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
