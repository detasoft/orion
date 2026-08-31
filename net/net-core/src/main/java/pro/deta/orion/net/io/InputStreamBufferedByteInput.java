package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class InputStreamBufferedByteInput
        implements BufferedByteInput, AutoCloseable {
    private final InputStream input;

    public InputStreamBufferedByteInput(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public int available() {
        try {
            return input.available();
        } catch (IOException error) {
            return 0;
        }
    }

    @Override
    public int readUnsignedByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Input stream reached end of stream");
        }
        return value;
    }

    @Override
    public ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException {
        requireNonNegativeLength(length);
        Objects.requireNonNull(allocator, "allocator");
        ByteBuf copy = allocator.buffer(length, length);
        try {
            while (copy.writableBytes() > 0) {
                int read = copy.writeBytes(input, copy.writableBytes());
                if (read <= 0) {
                    throw new EOFException("Input stream reached end of stream");
                }
            }
            return copy;
        } catch (Throwable error) {
            copy.release();
            throw error;
        }
    }

    @Override
    public int readInto(ByteBuf target, int maxLength) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonNegativeLength(maxLength);
        if (maxLength == 0 || !target.isWritable()) {
            return 0;
        }
        int length = Math.min(maxLength, target.writableBytes());
        int read = target.writeBytes(input, length);
        return Math.max(read, 0);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private static void requireNonNegativeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
    }
}
