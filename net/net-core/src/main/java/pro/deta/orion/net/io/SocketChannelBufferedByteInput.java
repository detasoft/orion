package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;

public final class SocketChannelBufferedByteInput implements BufferedByteInput, AutoCloseable {
    private final SocketChannel channel;
    private final ByteBuf inputBuffer;

    public SocketChannelBufferedByteInput(
            SocketChannel channel,
            ByteBufAllocator allocator,
            int inputBufferSize) {
        this.channel = Objects.requireNonNull(channel, "channel");
        if (inputBufferSize <= 0) {
            throw new IllegalArgumentException("inputBufferSize must be positive");
        }
        inputBuffer = allocator.directBuffer(inputBufferSize, inputBufferSize);
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
    public ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException {
        requireNonNegativeLength(length);
        Objects.requireNonNull(allocator, "allocator");
        ByteBuf copy = allocator.buffer(length, length);
        try {
            while (copy.writableBytes() > 0) {
                requireAvailable();
                int copied = Math.min(copy.writableBytes(), inputBuffer.readableBytes());
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
        channel.close();
    }

    private void requireAvailable() throws IOException {
        while (!inputBuffer.isReadable()) {
            if (!refill()) {
                throw new EOFException("Socket channel reached end of stream");
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
        ByteBuffer target = inputBuffer.nioBuffer(inputBuffer.writerIndex(), inputBuffer.writableBytes());
        int read = channel.read(target);
        if (read < 0) {
            return false;
        }
        inputBuffer.writerIndex(inputBuffer.writerIndex() + read);
        return read > 0;
    }

    private static void requireNonNegativeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
    }
}
