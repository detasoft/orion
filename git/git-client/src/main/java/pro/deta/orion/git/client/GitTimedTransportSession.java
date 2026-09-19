package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

final class GitTimedTransportSession implements GitClientTransportSession {
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true)
                            .name("orion-git-timeout-", 0).factory());
    private final GitClientTransportSession delegate;
    private final BufferedByteInputV2 input;
    private final BufferedByteOutput output;

    static GitClientTransportSession wrap(
            GitClientTransportSession delegate,
            GitClientOptions options) {
        return wrap(delegate, options, (action, timeout) -> {
            ScheduledFuture<?> future = WATCHDOG.schedule(
                    action, timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        });
    }

    static GitClientTransportSession wrap(
            GitClientTransportSession delegate,
            GitClientOptions options,
            Watchdog watchdog) {
        return new GitTimedTransportSession(delegate, options, watchdog);
    }

    private GitTimedTransportSession(
            GitClientTransportSession delegate,
            GitClientOptions options,
            Watchdog watchdog) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(options, "options");
        input = new BufferedByteInputV2(new TimedInput(delegate.input(), options.readTimeout(), watchdog));
        output = new TimedOutput(delegate.output(), options.writeTimeout(), watchdog);
    }

    @Override
    public BufferedByteInputV2 input() {
        return input;
    }

    @Override
    public BufferedByteOutput output() {
        return output;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private <T> T within(Duration timeout, Watchdog watchdog, IoCall<T> call)
            throws IOException {
        AtomicInteger state = new AtomicInteger();
        Cancellation cancellation = watchdog.schedule(() -> {
            if (state.compareAndSet(0, 2)) {
                closeAfterTimeout();
            }
        }, timeout);
        try {
            T value = call.call();
            if (!state.compareAndSet(0, 1)) {
                throw timeoutFailure();
            }
            return value;
        } catch (IOException error) {
            if (state.get() == 2) {
                throw timeoutFailure(error);
            }
            if (error instanceof SocketTimeoutException) {
                throw timeoutFailure(error);
            }
            state.compareAndSet(0, 1);
            if (state.get() == 2) {
                throw timeoutFailure(error);
            }
            throw error;
        } finally {
            cancellation.cancel();
        }
    }

    private void closeAfterTimeout() {
        try {
            close();
        } catch (IOException ignored) {
            // The timed-out I/O has the primary outcome.
        }
    }

    private static GitClientTransportException timeoutFailure() {
        return timeoutFailure(null);
    }

    private static GitClientTransportException timeoutFailure(Throwable cause) {
        return new GitClientTransportException(
                GitClientFailure.Kind.TIMEOUT,
                true,
                "Git transport I/O timed out",
                cause);
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T call() throws IOException;
    }

    @FunctionalInterface
    interface Watchdog {
        Cancellation schedule(Runnable action, Duration timeout);
    }

    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }

    private final class TimedInput implements BufferedByteInputV2.Source {
        private final BufferedByteInputV2 delegate;
        private final Duration timeout;
        private final Watchdog watchdog;

        private TimedInput(
                BufferedByteInputV2 delegate,
                Duration timeout,
                Watchdog watchdog) {
            this.delegate = delegate;
            this.timeout = timeout;
            this.watchdog = watchdog;
        }

        @Override
        public ByteBuffer read() throws IOException {
            return within(timeout, watchdog, delegate::buffer);
        }

        @Override
        public void release() {}

        @Override
        public void close() {}
    }

    private final class TimedOutput implements BufferedByteOutput {
        private final BufferedByteOutput delegate;
        private final Duration timeout;
        private final Watchdog watchdog;

        private TimedOutput(
                BufferedByteOutput delegate,
                Duration timeout,
                Watchdog watchdog) {
            this.delegate = delegate;
            this.timeout = timeout;
            this.watchdog = watchdog;
        }

        @Override
        public void write(ByteBuf buffer) throws IOException {
            within(timeout, watchdog, () -> {
                delegate.write(buffer);
                return null;
            });
        }

        @Override
        public void flush() throws IOException {
            within(timeout, watchdog, () -> {
                delegate.flush();
                return null;
            });
        }
    }
}
