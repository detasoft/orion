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
                new OneByteAtATimeInputStream("abc".getBytes(StandardCharsets.US_ASCII)),
                UnpooledByteBufAllocator.DEFAULT,
                8);
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
                new ByteArrayInputStream(new byte[0]),
                UnpooledByteBufAllocator.DEFAULT,
                8);
        ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(8, 8);
        try {
            assertThat(input.readInto(target, 8)).isZero();
        } finally {
            target.release();
            input.close();
        }
    }

    @Test
    void readCopyStillRequiresExactRequestedBytes() throws Exception {
        InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)),
                UnpooledByteBufAllocator.DEFAULT,
                8);
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
}
