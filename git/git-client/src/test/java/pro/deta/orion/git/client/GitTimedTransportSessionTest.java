package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitTimedTransportSessionTest {
    @Test
    void closesSessionAndReportsTimeoutForBlockedRead() {
        BlockingSession session = new BlockingSession();
        GitClientTransportSession timed = timed(session, Duration.ofMillis(10));

        assertThatThrownBy(() -> timed.input().readUnsignedByte())
                .isInstanceOf(GitClientTransportException.class)
                .extracting(error -> ((GitClientTransportException) error).kind())
                .isEqualTo(GitClientFailure.Kind.TIMEOUT);
        assertThat(session.closed.getCount()).isZero();
    }

    @Test
    void closesSessionAndReportsTimeoutForBlockedWrite() {
        BlockingWriteSession session = new BlockingWriteSession();
        GitClientTransportSession timed = timed(session, Duration.ofMillis(10));

        assertThatThrownBy(() -> timed.output().flush())
                .isInstanceOf(GitClientTransportException.class)
                .extracting(error -> ((GitClientTransportException) error).kind())
                .isEqualTo(GitClientFailure.Kind.TIMEOUT);
        assertThat(session.closed.getCount()).isZero();
    }

    @Test
    void completedReadIsNotClosedByLateWatchdog() throws Exception {
        CompletingSession session = new CompletingSession();
        ManualWatchdog watchdog = new ManualWatchdog();
        GitClientTransportSession timed = timed(
                session, Duration.ofMillis(10), watchdog);

        assertThat(timed.input().readUnsignedByte()).isEqualTo(1);
        watchdog.runExpiredAction();

        assertThat(session.closed).isFalse();
    }

    private static GitClientTransportSession timed(
            GitClientTransportSession session,
            Duration timeout) {
        return GitTimedTransportSession.wrap(session, new GitClientOptions(
                timeout, timeout, timeout, Duration.ofSeconds(1), 1));
    }

    private static GitClientTransportSession timed(
            GitClientTransportSession session,
            Duration timeout,
            GitTimedTransportSession.Watchdog watchdog) {
        return GitTimedTransportSession.wrap(session, new GitClientOptions(
                timeout, timeout, timeout, Duration.ofSeconds(1), 1), watchdog);
    }

    private static final class BlockingSession implements GitClientTransportSession {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public BufferedByteInputV2 input() {
            return new BufferedByteInputV2(new java.io.InputStream() {

                @Override
                public int read() throws IOException {
                    try {
                        closed.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                    throw new IOException("closed");
                }
            });
        }

        @Override
        public BufferedByteOutput output() {
            return EmptyOutput.INSTANCE;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class CompletingSession implements GitClientTransportSession {
        private volatile boolean closed;

        @Override
        public BufferedByteInputV2 input() {
            return new BufferedByteInputV2(new java.io.InputStream() {

                @Override
                public int read() {
                    return 1;
                }
            });
        }

        @Override
        public BufferedByteOutput output() {
            return EmptyOutput.INSTANCE;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class BlockingWriteSession
            implements GitClientTransportSession {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public BufferedByteInputV2 input() {
            return new BufferedByteInputV2(java.io.InputStream.nullInputStream());
        }

        @Override
        public BufferedByteOutput output() {
            return new BufferedByteOutput() {
                @Override
                public void write(ByteBuf buffer) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void flush() throws IOException {
                    try {
                        closed.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
            };
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private enum EmptyOutput implements BufferedByteOutput {
        INSTANCE;

        @Override
        public void write(ByteBuf buffer) {
        }

        @Override
        public void flush() {
        }
    }

    private static final class ManualWatchdog
            implements GitTimedTransportSession.Watchdog {
        private Runnable action;

        @Override
        public GitTimedTransportSession.Cancellation schedule(
                Runnable action,
                Duration timeout) {
            this.action = action;
            return () -> { };
        }

        private void runExpiredAction() {
            action.run();
        }
    }
}
