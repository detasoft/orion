package pro.deta.orion.transport.git;

import org.apache.sshd.common.io.IoOutputStream;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class MinaAsyncOutputStream extends OutputStream {
    private final IoOutputStream output;
    private final AtomicBoolean closed = new AtomicBoolean();

    MinaAsyncOutputStream(IoOutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return;
        }
        if (closed.get()) {
            throw new IOException("interactive terminal output is closed");
        }
        byte[] copy = Arrays.copyOfRange(bytes, offset, offset + length);
        IoWriteFuture future = output.writeBuffer(new ByteArrayBuffer(copy));
        await(future);
        Throwable failure = future.getException();
        if (failure != null) {
            throw failure("asynchronous terminal write failed", failure);
        }
        if (!future.isWritten()) {
            throw new IOException("asynchronous terminal write did not complete successfully");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            output.close(true);
        }
    }

    private static void await(IoWriteFuture future) throws IOException {
        CountDownLatch completed = new CountDownLatch(1);
        future.addListener(ignored -> completed.countDown());
        try {
            completed.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure =
                    new InterruptedIOException("asynchronous terminal write interrupted");
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
