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
    private final ByteBufAllocator allocator;
    private final ByteBuf inputBuffer;

    public InputStreamBufferedByteInput(
            InputStream input,
            ByteBufAllocator allocator,
            int inputBufferSize) {
        this.input = Objects.requireNonNull(input, "input");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        if (inputBufferSize <= 0) {
            throw new IllegalArgumentException("inputBufferSize must be positive");
        }
        inputBuffer = allocator.heapBuffer(inputBufferSize, inputBufferSize);
    }

    @Override
    public int available() {
        return inputBuffer.readableBytes();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        requireAvailable();
        return inputBuffer.readUnsignedByte();
    }

    @Override
    public ByteBuf readCopy(int length) throws IOException {
        requireNonNegativeLength(length);
        ByteBuf copy = allocator.buffer(length, length);
        try {
            while (copy.writableBytes() > 0) {
                requireAvailable();
                int copied = Math.min(
                        copy.writableBytes(),
                        inputBuffer.readableBytes());
                copy.writeBytes(inputBuffer, copied);
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
        if (!inputBuffer.isReadable() && !refill()) {
            return 0;
        }
        int copied = Math.min(
                Math.min(maxLength, target.writableBytes()),
                inputBuffer.readableBytes());
        target.writeBytes(inputBuffer, copied);
        return copied;
    }

    @Override
    public void close() throws IOException {
        inputBuffer.release();
        input.close();
    }

    private void requireAvailable() throws IOException {
        while (!inputBuffer.isReadable()) {
            if (!refill()) {
                throw new EOFException("Input stream reached end of stream");
            }
        }
    }

    private boolean refill() throws IOException {
        if (!inputBuffer.isWritable()) {
            inputBuffer.discardReadBytes();
        }
        if (!inputBuffer.isWritable()) {
            return true;
        }
        int writerIndex = inputBuffer.writerIndex();
        int read = input.read(
                inputBuffer.array(),
                inputBuffer.arrayOffset() + writerIndex,
                inputBuffer.writableBytes());
        if (read < 0) {
            return false;
        }
        inputBuffer.writerIndex(writerIndex + read);
        return read > 0;
    }

    private static void requireNonNegativeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
    }
}
