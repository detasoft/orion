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
    private final ByteBufAllocator allocator;
    private final ByteBuf inputBuffer;

    public SocketChannelBufferedByteInput(SocketChannel channel, ByteBufAllocator allocator, int inputBufferSize) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
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
    public void skipBytes(int length) throws IOException {
        requireNonNegativeLength(length);
        int remaining = length;
        while (remaining > 0) {
            requireAvailable();
            int skipped = Math.min(remaining, inputBuffer.readableBytes());
            inputBuffer.skipBytes(skipped);
            remaining -= skipped;
        }
    }

    @Override
    public ByteBuf readCopy(int length) throws IOException {
        requireNonNegativeLength(length);
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
    public void close() throws IOException {
        inputBuffer.release();
        channel.close();
    }

    private void requireAvailable() throws IOException {
        while (!inputBuffer.isReadable()) {
            refill();
        }
    }

    private void refill() throws IOException {
        if (!inputBuffer.isWritable()) {
            inputBuffer.discardReadBytes();
        }
        if (!inputBuffer.isWritable()) {
            return;
        }
        ByteBuffer target = inputBuffer.nioBuffer(inputBuffer.writerIndex(), inputBuffer.writableBytes());
        int read = channel.read(target);
        if (read < 0) {
            throw new EOFException("Socket channel reached end of stream");
        }
        inputBuffer.writerIndex(inputBuffer.writerIndex() + read);
    }

    private static void requireNonNegativeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
    }
}
