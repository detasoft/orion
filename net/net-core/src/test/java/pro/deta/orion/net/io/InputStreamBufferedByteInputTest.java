package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputStreamBufferedByteInputTest {
    @Test
    void readIntoReturnsAvailableBytesWithoutFillingRequestedLength()
            throws Exception {
        InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(
                new OneByteAtATimeInputStream("abc".getBytes(StandardCharsets.US_ASCII)));
        ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(8, 8);
        try {
            assertThat(input.readInto(target, 8)).isEqualTo(1);
            assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("a");
        } finally {
            target.release();
            input.close();
        }
    }

    @Test
    void readIntoReturnsZeroWhenEndOfStreamArrivesBeforeAnyByte()
            throws Exception {
        InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(
                new ByteArrayInputStream(new byte[0]));
        ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(8, 8);
        try {
            assertThat(input.readInto(target, 8)).isZero();
        } finally {
            target.release();
            input.close();
        }
    }

    @Test
    void readUnsignedByteReadsDirectlyWithoutPrefetching() throws Exception {
        RecordingInputStream source = new RecordingInputStream(
                "abc".getBytes(StandardCharsets.US_ASCII));
        InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(source);
        try {
            assertThat(input.readUnsignedByte()).isEqualTo('a');

            assertThat(source.singleByteReads).isEqualTo(1);
            assertThat(source.bulkReads).isZero();
            assertThat(source.available()).isEqualTo(2);
            assertThat(input.available()).isEqualTo(2);
        } finally {
            input.close();
        }
    }

    @Test
    void readCopyStillRequiresExactRequestedBytes() throws Exception {
        InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));
        try {
            assertThatThrownBy(() -> input.readCopy(4, UnpooledByteBufAllocator.DEFAULT))
                    .isInstanceOf(java.io.EOFException.class);
        } finally {
            input.close();
        }
    }

    private static final class OneByteAtATimeInputStream
            extends ByteArrayInputStream {

        private OneByteAtATimeInputStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            return super.read(buffer, offset, Math.min(length, 1));
        }
    }

    private static final class RecordingInputStream extends ByteArrayInputStream {
        private int singleByteReads;
        private int bulkReads;

        private RecordingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read() {
            singleByteReads++;
            return super.read();
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            bulkReads++;
            return super.read(buffer, offset, length);
        }
    }
}
