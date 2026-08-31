package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class QueueBufferedByteOutput implements BufferedByteOutput, AutoCloseable {
    private final Object lock = new Object();
    private final ArrayDeque<Byte> queue = new ArrayDeque<>();
    private final int capacity;
    private final Duration timeout;
    private boolean closed;

    QueueBufferedByteOutput(
            int capacity,
            Duration timeout) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        while (buffer.isReadable()) {
            writeByte(buffer.readByte());
        }
    }

    @Override
    public void flush() {
    }

    byte takeByte() throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (queue.isEmpty()) {
                if (closed) {
                    throw new IOException("Queue output is closed");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Timed out waiting for output bytes");
                }
                waitFor(remaining);
            }
            byte value = queue.removeFirst();
            lock.notifyAll();
            return value;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    private void writeByte(byte value) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (queue.size() >= capacity) {
                if (closed) {
                    throw new IOException("Queue output is closed");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Timed out waiting to write output bytes");
                }
                waitFor(remaining);
            }
            queue.addLast(value);
            lock.notifyAll();
        }
    }

    private void waitFor(long nanos) throws IOException {
        try {
            TimeUnit.NANOSECONDS.timedWait(lock, nanos);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while waiting for output bytes",
                    error);
        }
    }
}
