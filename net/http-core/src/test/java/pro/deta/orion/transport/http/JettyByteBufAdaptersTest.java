package pro.deta.orion.transport.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JettyByteBufAdaptersTest {
    @Test
    void inputReadsFromServletRequestBodyIntoBufferedByteInput() throws Exception {
        try (JettyBufferedByteInput input = new JettyBufferedByteInput(
                new ByteArrayServletInputStream("abcdef".getBytes(StandardCharsets.US_ASCII)),
                UnpooledByteBufAllocator.DEFAULT,
                4)) {
            assertThat(input.available()).isZero();
            assertThat(input.readUnsignedByte()).isEqualTo('a');

            ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(2, 2);
            ByteBuf copy = null;
            try {
                assertThat(input.readInto(target, 2)).isEqualTo(2);
                assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("bc");
                copy = input.readCopy(3, UnpooledByteBufAllocator.DEFAULT);
                assertThat(copy.toString(StandardCharsets.US_ASCII)).isEqualTo("def");
                assertThat(input.available()).isZero();
            } finally {
                target.release();
                if (copy != null) {
                    copy.release();
                }
            }
        }
    }

    @Test
    void outputWritesHeapByteBufArrayToServletResponseBodyWithoutAdapterCopy() throws Exception {
        RecordingServletOutputStream body = new RecordingServletOutputStream();
        BufferedByteOutput output = new JettyBufferedByteOutput(body);
        byte[] bytes = "xxhello".getBytes(StandardCharsets.US_ASCII);
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            buffer.skipBytes(2);

            output.write(buffer);
            output.flush();

            assertThat(body.toString(StandardCharsets.US_ASCII)).isEqualTo("hello");
            assertThat(body.lastBuffer).isSameAs(bytes);
            assertThat(body.lastOffset).isEqualTo(2);
            assertThat(body.lastLength).isEqualTo(5);
        } finally {
            buffer.release();
        }
    }

    @Test
    void outputWritesDirectByteBufToServletResponseBody() throws Exception {
        BufferedByteOutput output = new JettyBufferedByteOutput(new RecordingServletOutputStream());
        ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.directBuffer(5, 5);
        try {
            buffer.writeCharSequence("hello", StandardCharsets.US_ASCII);

            output.write(buffer);
        } finally {
            buffer.release();
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] data) {
            input = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return input.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }

    private static final class RecordingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream output;
        private byte[] lastBuffer;
        private int lastOffset;
        private int lastLength;

        private RecordingServletOutputStream() {
            output = new ByteArrayOutputStream();
        }

        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            lastBuffer = buffer;
            lastOffset = offset;
            lastLength = length;
            output.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        private String toString(java.nio.charset.Charset charset) {
            return output.toString(charset);
        }
    }
}
