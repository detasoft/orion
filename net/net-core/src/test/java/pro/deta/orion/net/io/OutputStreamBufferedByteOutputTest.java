package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OutputStreamBufferedByteOutputTest {
    @Test
    void writesReadableBytesToOutputStream() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput output =
                new OutputStreamBufferedByteOutput(bytes);
        ByteBuf buffer = Unpooled.wrappedBuffer(
                "xxhello".getBytes(StandardCharsets.US_ASCII));
        try {
            buffer.skipBytes(2);

            output.write(buffer);
            output.flush();

            assertThat(bytes.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("hello");
        } finally {
            buffer.release();
        }
    }

    @Test
    void writesByteArrayRangeToOutputStream() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput output =
                new OutputStreamBufferedByteOutput(bytes);

        output.write("xxhello!".getBytes(StandardCharsets.US_ASCII), 2, 5);
        output.flush();

        assertThat(bytes.toString(StandardCharsets.US_ASCII))
                .isEqualTo("hello");
    }

    @Test
    void writesDirectByteBufToOutputStream() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput output =
                new OutputStreamBufferedByteOutput(bytes);
        ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.directBuffer(5, 5);
        try {
            buffer.writeCharSequence("hello", StandardCharsets.US_ASCII);

            output.write(buffer);
            output.flush();

            assertThat(bytes.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("hello");
        } finally {
            buffer.release();
        }
    }
}
