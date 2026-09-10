package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.journal.FileSystemSessionJournalStorage;
import pro.deta.orion.agent.server.journal.JournalAppendResult;
import pro.deta.orion.agent.server.journal.JournalReadResult;
import pro.deta.orion.agent.server.journal.JournalStorageConfig;
import pro.deta.orion.agent.server.journal.JournalStorageException;
import pro.deta.orion.agent.server.journal.SessionJournalStorage;
import pro.deta.orion.agent.server.replication.SessionReplicationService;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JettySessionReplicationLivePeerTest {
    private static final long TIMEOUT_SECONDS = 5;
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec PROTOCOL = new AgentProtocolCodec(LIMITS);
    private static final SessionEventCodec EVENTS = new SessionEventCodec(LIMITS);
    private static final AgentId AGENT_ID = new AgentId("agent-1");
    private final AtomicInteger peerIds = new AtomicInteger();

    @TempDir
    Path root;

    @Test
    void admitsOnlyValidSessionPostsWithEstablishedContext() throws Exception {
        try (Peer established = new Peer(Optional.of(AGENT_ID));
             Peer missing = new Peer(Optional.empty())) {
            HeadersFrame accepted = established.request("POST", "/agent/session/session-1")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            HeadersFrame unauthenticated = missing.request("POST", "/agent/session/session-1")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(status(accepted)).isEqualTo(200);
            assertThat(accepted.isEndStream()).isFalse();
            assertThat(status(unauthenticated)).isEqualTo(401);
            assertThat(unauthenticated.isEndStream()).isTrue();
        }
    }

    @Test
    void rejectsWrongMethodUnknownPathAndInvalidSessionId() throws Exception {
        try (Peer peer = new Peer(Optional.of(AGENT_ID))) {
            assertThat(status(peer.request("GET", "/agent/session/session-1")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))).isEqualTo(405);
            assertThat(status(peer.request("POST", "/agent/other/session-1")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))).isEqualTo(404);
            assertThat(status(peer.request("POST", "/agent/session/")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))).isEqualTo(400);
        }
    }

    @Test
    void decodesSplitOpenAndPersistsCoalescedRawEventsBeforeAcknowledging() throws Exception {
        SessionId sessionId = new SessionId("session-1");
        SessionEventRecord first = event(1, (byte) 11);
        SessionEventRecord second = event(2, (byte) 22);
        try (Peer peer = new Peer(Optional.of(AGENT_ID))) {
            Exchange exchange = peer.exchange("POST", "/agent/session/session-1");
            assertThat(status(exchange.headers().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                    .isEqualTo(200);

            byte[] open = PROTOCOL.encode(open(sessionId));
            int split = open.length / 2;
            exchange.send(Arrays.copyOfRange(open, 0, split), false);
            exchange.send(Arrays.copyOfRange(open, split, open.length), false);
            assertThat(exchange.nextMessage()).isEqualTo(
                    new AgentMessage.SessionSync(sessionId, Optional.empty()));

            exchange.send(concatenate(
                    first.encodedRecord().toByteArray(), second.encodedRecord().toByteArray()), true);
            assertThat(exchange.nextMessage()).isEqualTo(
                    new AgentMessage.SessionSync(sessionId, Optional.of(new EventId(2))));
            assertThat(exchange.awaitEnd()).isTrue();

            JournalReadResult stored = peer.storage.readAfter(sessionId, Optional.empty());
            assertThat(stored.records()).containsExactly(first, second);
            assertThat(stored.records())
                    .extracting(record -> record.encodedRecord().toByteArray())
                    .containsExactly(
                            first.encodedRecord().toByteArray(),
                            second.encodedRecord().toByteArray());
        }
    }

    @Test
    void resetsOnlyTheStreamWhoseOpenDisagreesWithItsPath() throws Exception {
        try (Peer peer = new Peer(Optional.of(AGENT_ID))) {
            Exchange invalid = peer.exchange("POST", "/agent/session/session-1");
            Exchange healthy = peer.exchange("POST", "/agent/session/session-2");
            invalid.awaitAccepted();
            healthy.awaitAccepted();

            invalid.send(PROTOCOL.encode(open(new SessionId("different-session"))), false);
            assertThat(invalid.awaitReset()).isEqualTo(ErrorCode.PROTOCOL_ERROR.code);

            healthy.send(PROTOCOL.encode(open(new SessionId("session-2"))), false);
            assertThat(healthy.nextMessage()).isEqualTo(new AgentMessage.SessionSync(
                    new SessionId("session-2"), Optional.empty()));
        }
    }

    @Test
    void resetsAnIncompleteOpeningItemAtEndOfStream() throws Exception {
        try (Peer peer = new Peer(Optional.of(AGENT_ID))) {
            Exchange exchange = peer.exchange("POST", "/agent/session/session-1");
            exchange.awaitAccepted();
            byte[] encoded = PROTOCOL.encode(open(new SessionId("session-1")));

            exchange.send(Arrays.copyOf(encoded, encoded.length - 1), true);

            assertThat(exchange.awaitReset()).isEqualTo(ErrorCode.PROTOCOL_ERROR.code);
        }
    }

    @Test
    void slowAppendBackpressuresOnlyItsOwnStream() throws Exception {
        SessionId slowId = new SessionId("slow-session");
        SessionId fastId = new SessionId("fast-session");
        try (Peer peer = new Peer(Optional.of(AGENT_ID))) {
            Exchange slow = peer.open(slowId);
            Exchange fast = peer.open(fastId);
            peer.storage.block(slowId, BlockPoint.BEFORE_DURABILITY);

            slow.send(event(1, (byte) 1).encodedRecord().toByteArray(), false);
            assertThat(peer.storage.awaitBlockedAppend()).isTrue();

            fast.send(event(1, (byte) 2).encodedRecord().toByteArray(), false);
            assertThat(fast.nextMessage()).isEqualTo(new AgentMessage.SessionSync(
                    fastId, Optional.of(new EventId(1))));
            assertThat(slow.hasMessage()).isFalse();

            peer.storage.releaseBlockedAppend();
            assertThat(slow.nextMessage()).isEqualTo(new AgentMessage.SessionSync(
                    slowId, Optional.of(new EventId(1))));
        }
    }

    @Test
    void reconnectAlwaysUsesStorageAcrossDisconnectBoundaries() throws Exception {
        try (Peer peer = new Peer(Optional.of(AGENT_ID))) {
            SessionId before = new SessionId("before-append");
            Exchange abandoned = peer.open(before);
            abandoned.reset();
            Exchange beforeRetry = peer.open(before);
            assertThat(beforeRetry.hasMessage()).isFalse();

            SessionId during = new SessionId("during-append");
            Exchange interrupted = peer.open(during);
            peer.storage.block(during, BlockPoint.BEFORE_DURABILITY);
            interrupted.send(event(1, (byte) 3).encodedRecord().toByteArray(), false);
            assertThat(peer.storage.awaitBlockedAppend()).isTrue();
            interrupted.reset();
            peer.storage.releaseBlockedAppend();
            assertThat(peer.storage.awaitAppendCompleted()).isTrue();
            assertThat(peer.reopen(during)).isEqualTo(new AgentMessage.SessionSync(
                    during, Optional.of(new EventId(1))));

            SessionId durable = new SessionId("durable-before-ack");
            Exchange unacknowledged = peer.open(durable);
            peer.storage.block(durable, BlockPoint.AFTER_DURABILITY);
            unacknowledged.send(event(1, (byte) 4).encodedRecord().toByteArray(), false);
            assertThat(peer.storage.awaitBlockedAppend()).isTrue();
            unacknowledged.reset();
            peer.storage.releaseBlockedAppend();
            assertThat(peer.storage.awaitAppendCompleted()).isTrue();
            assertThat(peer.reopen(durable)).isEqualTo(new AgentMessage.SessionSync(
                    durable, Optional.of(new EventId(1))));
        }
    }

    private static AgentMessage.SessionOpen open(SessionId sessionId) {
        return new AgentMessage.SessionOpen(
                sessionId, Optional.empty(), Optional.empty(), AgentMessage.SessionState.RUNNING);
    }

    private static SessionEventRecord event(long eventId, byte payload)
            throws AgentProtocolException {
        return EVENTS.decode(EVENTS.encode(
                new EventId(eventId),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{payload}))));
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static int status(HeadersFrame frame) {
        return ((MetaData.Response) frame.getMetaData()).getStatus();
    }

    private final class Peer implements AutoCloseable {
        private final BlockingStorage storage = new BlockingStorage(
                new FileSystemSessionJournalStorage(
                        root.resolve("peer-" + peerIds.incrementAndGet()),
                        new JournalStorageConfig(LIMITS)));
        private final Server server = new Server();
        private final HTTP2Client client = new HTTP2Client();
        private final JettySessionReplicationEndpoint endpoint;
        private final ServerConnector connector;
        private final Session session;

        private Peer(Optional<AgentId> agentId) throws Exception {
            SessionReplicationService service = new SessionReplicationService(
                    storage, (ignoredAgent, ignoredSession, ignoredGap) -> { });
            endpoint = new JettySessionReplicationEndpoint(service, ignored -> agentId, LIMITS);
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory() {
                @Override
                protected ServerSessionListener newSessionListener(
                        Connector ignored,
                        EndPoint endPoint) {
                    return endpoint;
                }
            };
            connector = new ServerConnector(server, h2);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            server.start();
            client.start();
            session = client.connect(
                    new InetSocketAddress("127.0.0.1", connector.getLocalPort()),
                    new Session.Listener() { }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private CompletableFuture<HeadersFrame> request(String method, String path) {
            return exchange(method, path).headers();
        }

        private Exchange exchange(String method, String path) {
            Exchange exchange = new Exchange();
            MetaData.Request request = new MetaData.Request(
                    method,
                    HttpURI.from("http://localhost" + path),
                    HttpVersion.HTTP_2,
                    HttpFields.EMPTY);
            session.newStream(new HeadersFrame(request, null, false), exchange)
                    .whenComplete((stream, failure) -> {
                        if (failure == null) {
                            exchange.stream().complete(stream);
                        } else {
                            exchange.fail(failure);
                        }
                    });
            return exchange;
        }

        private Exchange open(SessionId sessionId) throws Exception {
            Exchange exchange = exchange("POST", "/agent/session/" + sessionId.value());
            exchange.awaitAccepted();
            exchange.send(PROTOCOL.encode(JettySessionReplicationLivePeerTest.open(sessionId)), false);
            assertThat(exchange.nextMessage()).isEqualTo(
                    new AgentMessage.SessionSync(sessionId, Optional.empty()));
            return exchange;
        }

        private AgentMessage reopen(SessionId sessionId) throws Exception {
            Exchange exchange = exchange("POST", "/agent/session/" + sessionId.value());
            exchange.awaitAccepted();
            exchange.send(PROTOCOL.encode(JettySessionReplicationLivePeerTest.open(sessionId)), false);
            return exchange.nextMessage();
        }

        @Override
        public void close() throws Exception {
            try {
                session.close(0, "test complete", Callback.NOOP);
            } finally {
                client.stop();
                server.stop();
                server.join();
                endpoint.close();
                storage.close();
            }
        }
    }

    private static final class Exchange implements Stream.Listener {
        private final CompletableFuture<HeadersFrame> headers = new CompletableFuture<>();
        private final CompletableFuture<Stream> stream = new CompletableFuture<>();
        private final CompletableFuture<Integer> reset = new CompletableFuture<>();
        private final CompletableFuture<Boolean> ended = new CompletableFuture<>();
        private final LinkedBlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();

        @Override
        public void onHeaders(Stream receivedStream, HeadersFrame frame) {
            headers.complete(frame);
            if (!frame.isEndStream()) {
                receivedStream.demand();
            }
        }

        @Override
        public void onDataAvailable(Stream receivedStream) {
            Stream.Data received = receivedStream.readData();
            if (received == null) {
                receivedStream.demand();
                return;
            }
            boolean endStream = received.frame().isEndStream();
            try {
                ByteBuffer source = received.frame().getByteBuffer();
                byte[] copy = new byte[source.remaining()];
                source.get(copy);
                if (copy.length > 0) {
                    messages.add(copy);
                }
            } finally {
                received.release();
            }
            if (endStream) {
                ended.complete(true);
            } else {
                receivedStream.demand();
            }
        }

        @Override
        public void onReset(Stream ignored, ResetFrame frame, Callback callback) {
            reset.complete(frame.getError());
            callback.succeeded();
        }

        private CompletableFuture<HeadersFrame> headers() {
            return headers;
        }

        private CompletableFuture<Stream> stream() {
            return stream;
        }

        private void awaitAccepted() throws Exception {
            assertThat(status(headers.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))).isEqualTo(200);
        }

        private void send(byte[] bytes, boolean endStream) throws Exception {
            Stream target = stream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            target.data(new DataFrame(target.getId(), ByteBuffer.wrap(bytes), endStream))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private AgentMessage nextMessage() throws Exception {
            byte[] encoded = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(encoded).as("response DATA frame").isNotNull();
            return PROTOCOL.decode(encoded);
        }

        private boolean hasMessage() throws InterruptedException {
            return messages.poll(100, TimeUnit.MILLISECONDS) != null;
        }

        private boolean awaitEnd() throws Exception {
            return ended.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private int awaitReset() throws Exception {
            return reset.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void reset() throws Exception {
            Stream target = stream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            target.reset(new ResetFrame(target.getId(), ErrorCode.CANCEL_STREAM_ERROR.code))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void fail(Throwable failure) {
            headers.completeExceptionally(failure);
            stream.completeExceptionally(failure);
            reset.completeExceptionally(failure);
            ended.completeExceptionally(failure);
        }
    }

    private static final class BlockingStorage implements SessionJournalStorage {
        private final SessionJournalStorage delegate;
        private volatile CountDownLatch appendEntered = new CountDownLatch(1);
        private volatile CountDownLatch allowAppend = new CountDownLatch(1);
        private volatile CountDownLatch appendCompleted = new CountDownLatch(1);
        private volatile SessionId blockedSession;
        private volatile BlockPoint blockPoint;

        private BlockingStorage(SessionJournalStorage delegate) {
            this.delegate = delegate;
        }

        private synchronized void block(SessionId sessionId, BlockPoint point) {
            appendEntered = new CountDownLatch(1);
            allowAppend = new CountDownLatch(1);
            appendCompleted = new CountDownLatch(1);
            blockedSession = sessionId;
            blockPoint = point;
        }

        private boolean awaitBlockedAppend() throws InterruptedException {
            return appendEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void releaseBlockedAppend() {
            allowAppend.countDown();
        }

        private boolean awaitAppendCompleted() throws InterruptedException {
            return appendCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public Optional<EventId> firstEventId(SessionId sessionId) throws JournalStorageException {
            return delegate.firstEventId(sessionId);
        }

        @Override
        public Optional<EventId> lastEventId(SessionId sessionId) throws JournalStorageException {
            return delegate.lastEventId(sessionId);
        }

        @Override
        public JournalAppendResult append(SessionId sessionId, List<SessionEventRecord> records)
                throws JournalStorageException {
            boolean blocked = sessionId.equals(blockedSession);
            if (blocked && blockPoint == BlockPoint.BEFORE_DURABILITY) {
                awaitRelease();
            }
            JournalAppendResult result = delegate.append(sessionId, records);
            if (blocked && blockPoint == BlockPoint.AFTER_DURABILITY) {
                awaitRelease();
            }
            if (blocked) {
                appendCompleted.countDown();
            }
            return result;
        }

        private void awaitRelease() throws JournalStorageException {
                appendEntered.countDown();
            try {
                if (!allowAppend.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new JournalStorageException(
                            JournalStorageException.Reason.IO_FAILURE,
                            "Timed out waiting to release append");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new JournalStorageException(
                        JournalStorageException.Reason.IO_FAILURE,
                        "Interrupted while waiting to append",
                        failure);
            }
        }

        @Override
        public JournalReadResult readAfter(SessionId sessionId, Optional<EventId> after)
                throws JournalStorageException {
            return delegate.readAfter(sessionId, after);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private enum BlockPoint {
        BEFORE_DURABILITY,
        AFTER_DURABILITY
    }
}
