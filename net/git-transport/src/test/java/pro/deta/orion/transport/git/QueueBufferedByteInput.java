package pro.deta.orion.transport.git;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class QueueBufferedByteInput implements BufferedByteInput, AutoCloseable {
    private final Object lock = new Object();
    private final ArrayDeque<Byte> queue = new ArrayDeque<>();
    private final Duration timeout;
    private boolean closed;

    QueueBufferedByteInput(
            Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public int available() {
        synchronized (lock) {
            return queue.size();
        }
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return awaitByte() & 0xff;
    }

    @Override
    public ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException {
        requireNonNegativeLength(length);
        Objects.requireNonNull(allocator, "allocator");
        ByteBuf copy = allocator.buffer(length, length);
        try {
            while (copy.writableBytes() > 0) {
                copy.writeByte(awaitByte());
            }
            return copy;
        } catch (Throwable error) {
            copy.release();
            throw error;
        }
    }

    @Override
    public int readInto(
            ByteBuf target,
            int maxLength) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonNegativeLength(maxLength);
        if (maxLength == 0 || !target.isWritable()) {
            return 0;
        }
        target.writeByte(awaitByte());
        int copied = 1;
        synchronized (lock) {
            int limit = Math.min(maxLength, target.writableBytes());
            while (copied < limit && !queue.isEmpty()) {
                target.writeByte(queue.removeFirst());
                copied++;
            }
        }
        return copied;
    }

    void feed(String ascii) {
        feed(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    void feed(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Queue input is closed");
            }
            for (byte value : bytes) {
                queue.addLast(value);
            }
            lock.notifyAll();
        }
    }

    void end() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    @Override
    public void close() {
        end();
    }

    private byte awaitByte() throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (queue.isEmpty()) {
                if (closed) {
                    throw new EOFException("Queue input reached end of stream");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Timed out waiting for input bytes");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while waiting for input bytes",
                            error);
                }
            }
            return queue.removeFirst();
        }
    }

    private static void requireNonNegativeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
    }
}
