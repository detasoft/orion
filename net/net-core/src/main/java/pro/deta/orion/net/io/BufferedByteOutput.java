package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;

public interface BufferedByteOutput {
    int DEFAULT_BUFFER_CAPACITY = 64 * 1024;

    default ByteBuf getByteBuf() {
        return Unpooled.buffer(
                DEFAULT_BUFFER_CAPACITY,
                DEFAULT_BUFFER_CAPACITY);
    }

    default void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    default void write(
            byte[] bytes,
            int offset,
            int length) throws IOException {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes, offset, length);
        try {
            write(buffer);
        } finally {
            buffer.release();
        }
    }

    void write(ByteBuf buffer) throws IOException;

    void flush() throws IOException;
}
