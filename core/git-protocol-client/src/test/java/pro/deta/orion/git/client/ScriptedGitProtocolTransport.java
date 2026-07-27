package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class ScriptedGitProtocolTransport implements GitProtocolTransport {
    private final Deque<byte[]> expectedWrites;
    private final Deque<byte[]> reads;
    private final GitProtocolTransportException openFailure;
    private final GitProtocolTransportException writeFailure;
    private final GitProtocolTransportException readFailure;
    private final GitProtocolTransportException closeFailure;
    private GitProtocolService openedService;
    private URI openedUri;
    private GitProtocolTransportOptions openedOptions;
    private Session session;

    public ScriptedGitProtocolTransport(List<byte[]> expectedWrites, List<byte[]> reads) {
        this(expectedWrites, reads, null, null, null);
    }

    public ScriptedGitProtocolTransport(
            List<byte[]> expectedWrites,
            List<byte[]> reads,
            GitProtocolTransportException readFailure) {
        this(expectedWrites, reads, null, null, readFailure);
    }

    public ScriptedGitProtocolTransport(
            List<byte[]> expectedWrites,
            List<byte[]> reads,
            GitProtocolTransportException openFailure,
            GitProtocolTransportException writeFailure,
            GitProtocolTransportException readFailure) {
        this(expectedWrites, reads, openFailure, writeFailure, readFailure, null);
    }

    public ScriptedGitProtocolTransport(
            List<byte[]> expectedWrites,
            List<byte[]> reads,
            GitProtocolTransportException openFailure,
            GitProtocolTransportException writeFailure,
            GitProtocolTransportException readFailure,
            GitProtocolTransportException closeFailure) {
        this.expectedWrites = copies(expectedWrites);
        this.reads = copies(reads);
        this.openFailure = openFailure;
        this.writeFailure = writeFailure;
        this.readFailure = readFailure;
        this.closeFailure = closeFailure;
    }

    @Override
    public GitProtocolSession open(
            GitProtocolService service,
            URI remoteUri,
            GitProtocolTransportOptions options) throws GitProtocolTransportException {
        if (openFailure != null) {
            throw openFailure;
        }
        openedService = service;
        openedUri = remoteUri;
        openedOptions = options;
        session = new Session();
        return session;
    }

    public GitProtocolService openedService() {
        return openedService;
    }

    public URI openedUri() {
        return openedUri;
    }

    public GitProtocolTransportOptions openedOptions() {
        return openedOptions;
    }

    public boolean closed() {
        return session != null && session.closed;
    }

    public int closeCalls() {
        return session == null ? 0 : session.closeCalls;
    }

    private final class Session implements GitProtocolSession {
        private boolean closed;
        private boolean writeFailed;
        private boolean readFailed;
        private int closeCalls;

        @Override
        public void write(ByteBuf chunk) throws GitProtocolTransportException {
            if (writeFailure != null && !writeFailed) {
                writeFailed = true;
                throw writeFailure;
            }
            byte[] actual = new byte[chunk.readableBytes()];
            chunk.getBytes(chunk.readerIndex(), actual);
            byte[] expected = expectedWrites.pollFirst();
            if (expected == null || !Arrays.equals(actual, expected)) {
                throw new GitProtocolTransportException(
                        GitProtocolTransportException.Phase.WRITE,
                        false,
                        "Unexpected scripted write");
            }
        }

        @Override
        public ByteBuf read() throws GitProtocolTransportException {
            if (readFailure != null && !readFailed) {
                readFailed = true;
                throw readFailure;
            }
            byte[] next = reads.pollFirst();
            return next == null ? null : Unpooled.wrappedBuffer(next);
        }

        @Override
        public void close() throws GitProtocolTransportException {
            if (closed) {
                return;
            }
            closed = true;
            closeCalls++;
            reads.clear(); // discard any unread scripted inbound entries
            if (closeFailure != null) {
                throw closeFailure;
            }
            if (!expectedWrites.isEmpty()) {
                throw new GitProtocolTransportException(
                        GitProtocolTransportException.Phase.CLOSE,
                        false,
                        "Scripted exchange closed before all writes");
            }
        }
    }

    private static Deque<byte[]> copies(List<byte[]> values) {
        Deque<byte[]> copies = new ArrayDeque<>();
        for (byte[] value : values) {
            copies.addLast(Arrays.copyOf(value, value.length));
        }
        return copies;
    }
}
