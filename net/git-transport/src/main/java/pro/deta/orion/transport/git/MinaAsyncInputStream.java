package pro.deta.orion.transport.git;

import org.apache.sshd.common.io.IoInputStream;
import org.apache.sshd.common.io.IoReadFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class MinaAsyncInputStream extends InputStream {
    private final IoInputStream input;
    private final AtomicBoolean closed = new AtomicBoolean();

    MinaAsyncInputStream(IoInputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException("interactive terminal input requires bulk reads");
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        if (closed.get()) {
            throw new IOException("interactive terminal input is closed");
        }
        Buffer buffer = new ByteArrayBuffer(length, false);
        IoReadFuture future = input.read(buffer);
        await(future);
        Throwable failure = future.getException();
        if (failure instanceof EOFException) {
            return -1;
        }
        if (failure != null) {
            throw failure("asynchronous terminal read failed", failure);
        }
        int count = future.getRead();
        if (count < 0) {
            return -1;
        }
        Buffer completed = future.getBuffer();
        if (completed == null || count > completed.available()) {
            throw new IOException("asynchronous terminal read returned an invalid buffer");
        }
        completed.getRawBytes(bytes, offset, count);
        return count;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            input.close(true);
        }
    }

    private static void await(IoReadFuture future) throws IOException {
        CountDownLatch completed = new CountDownLatch(1);
        future.addListener(ignored -> completed.countDown());
        try {
            completed.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure =
                    new InterruptedIOException("asynchronous terminal read interrupted");
            failure.initCause(exception);
            throw failure;
        }
    }

    private static IOException failure(String message, Throwable cause) {
        if (cause instanceof IOException exception) {
            return exception;
        }
        return new IOException(message, cause);
    }
}
