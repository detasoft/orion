package pro.deta.orion.transport.git;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class QueueByteSource implements BufferedByteInputV2.Source {
    private final Object lock = new Object();
    private final ArrayDeque<Byte> queue = new ArrayDeque<>();
    private final Duration timeout;
    private boolean closed;

    QueueByteSource(
            Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public ByteBuffer read() throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(8192);
        try {
            bytes.put(awaitByte());
        } catch (EOFException end) {
            return null;
        }
        synchronized (lock) {
            while (bytes.hasRemaining() && !queue.isEmpty()) {
                bytes.put(queue.removeFirst());
            }
        }
        return bytes.flip();
    }

    @Override
    public void release() {}

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

}
