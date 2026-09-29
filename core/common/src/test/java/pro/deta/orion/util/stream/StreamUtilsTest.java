package pro.deta.orion.util.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamUtilsTest {

    @Test
    void emptyStreamReturnsEofWithoutChangingBuffer() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{7, 8, 9, 10});
        buffer.position(1);
        buffer.limit(3);
        byte[] before = buffer.array().clone();

        int count = assertDoesNotThrow(() -> StreamUtils.readStreamInto(buffer, InputStream.nullInputStream()));

        assertEquals(-1, count);
        assertEquals(1, buffer.position());
        assertEquals(3, buffer.limit());
        assertArrayEquals(before, buffer.array());
    }

    @Test
    void eofAfterDataPreservesPreviouslyReadBytes() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.put((byte) 7);
        InputStream input = new ByteArrayInputStream(new byte[]{8, 9});

        assertEquals(2, StreamUtils.readStreamInto(buffer, input));
        assertEquals(3, buffer.position());
        byte[] before = buffer.array().clone();

        assertEquals(-1, assertDoesNotThrow(() -> StreamUtils.readStreamInto(buffer, input)));
        assertEquals(-1, assertDoesNotThrow(() -> StreamUtils.readStreamInto(buffer, input)));
        assertEquals(3, buffer.position());
        assertArrayEquals(before, buffer.array());
        assertArrayEquals(new byte[]{7, 8, 9}, StreamUtils.getByteArray(buffer));
    }

    @Test
    void retriesZeroLengthReadBeforeAppendingData() throws IOException {
        InputStream input = new ByteArrayInputStream(new byte[]{8, 9}) {
            private boolean firstRead = true;

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (firstRead) {
                    firstRead = false;
                    return 0;
                }
                return super.read(bytes, offset, length);
            }
        };
        ByteBuffer buffer = ByteBuffer.allocate(16);

        assertEquals(2, StreamUtils.readStreamInto(buffer, input));
        assertArrayEquals(new byte[]{8, 9}, StreamUtils.getByteArray(buffer));
    }

    @Test
    void propagatesReadFailureWithoutChangingBuffer() {
        IOException failure = new IOException("Read failed");
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        };
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{7, 8, 9});
        buffer.position(1);

        assertSame(failure, assertThrows(IOException.class, () -> StreamUtils.readStreamInto(buffer, input)));
        assertEquals(1, buffer.position());
        assertArrayEquals(new byte[]{7, 8, 9}, buffer.array());
    }
}
