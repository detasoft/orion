package pro.deta.orion.agentd.transport;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.util.CertUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JettyHttp2LivePeerTest {
    private static final long TIMEOUT_SECONDS = 5;
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());
    private static final AgentMessage FIRST_MESSAGE = new AgentMessage.RequestSessionList();
    private static final AgentMessage SECOND_MESSAGE = new AgentMessage.SessionSync(
            new SessionId("second"), java.util.Optional.empty());
    private static final AgentMessage THIRD_MESSAGE = new AgentMessage.SessionSync(
            new SessionId("third"), java.util.Optional.of(new pro.deta.orion.agent.protocol.EventId(3)));
    private static final byte[] FIRST_RECORD = encode(FIRST_MESSAGE);
    private static final byte[] SECOND_RECORD = encode(SECOND_MESSAGE);
    private static final byte[] THIRD_RECORD = encode(THIRD_MESSAGE);

    @Test
    void opensBidirectionalPostAndReassemblesFragmentedAndCoalescedCbor() throws Exception {
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> {
                stream.data(data(stream, java.util.Arrays.copyOf(FIRST_RECORD, 2)), Callback.from(() ->
                        stream.data(data(stream, sequence(
                                java.util.Arrays.copyOfRange(FIRST_RECORD, 2, FIRST_RECORD.length),
                                SECOND_RECORD)), Callback.NOOP)));
            });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            transport.onControlMessage(received::add);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            MetaData.Request request = peer.requests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getHttpURI().getPath()).isEqualTo("/agent/control");
            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(FIRST_MESSAGE);
            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(SECOND_MESSAGE);
        }
    }

    @Test
    void skipsOneSemanticControlFailureAndDeliversLaterMessages() throws Exception {
        byte[] invalidWelcome = HexFormat.of().parseHex(
                "8519800101016163a27163726564656e7469616c2d7365637265746178"
                        + "7163726564656e7469616c2d7365637265746179");
        try (LogCapture logs = new LogCapture();
             Peer peer = new Peer((server, stream, request, count) -> {
                 respond(stream, 200, false, () -> stream.data(
                         data(stream, sequence(FIRST_RECORD, invalidWelcome, SECOND_RECORD)), Callback.NOOP));
                 return Stream.Listener.AUTO_DISCARD;
             })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onControlMessage(received::add);
            transport.onSignal(signals::add);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(FIRST_MESSAGE);
            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(SECOND_MESSAGE);
            assertThat(signals.stream()).noneMatch(
                    signal -> signal.kind() == TransportSignal.Kind.DISCONNECTED);
            assertThat(logs.messages()).singleElement().asString()
                    .contains("stream=control", "reason=INVALID_FIELD", "itemLength=49")
                    .doesNotContain("credential-secret", HexFormat.of().formatHex(invalidWelcome));
        }
    }

    @Test
    void deliversControlPrefixBeforeStructuralFailureWithoutResynchronizing() throws Exception {
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> stream.data(
                    data(stream, sequence(FIRST_RECORD, new byte[]{(byte) 0xff}, SECOND_RECORD)),
                    Callback.NOOP));
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onControlMessage(received::add);
            transport.onSignal(signals::add);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(FIRST_MESSAGE);
            assertThat(await(signals, TransportSignal.Kind.DISCONNECTED).sessionId()).isNull();
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void keepsAcceptedControlPrefixWhenResetInvalidatesGenerationBeforeDelivery() throws Exception {
        CountDownLatch receiverEntered = new CountDownLatch(1);
        CountDownLatch releaseReceiver = new CountDownLatch(1);
        CompletableFuture<Stream> serverStream = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            serverStream.complete(stream);
            respond(stream, 200, false, () -> { });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            transport.onControlMessage(message -> {
                if (message.equals(FIRST_MESSAGE)) {
                    receiverEntered.countDown();
                    await(releaseReceiver);
                } else {
                    received.add(message);
                }
            });
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Stream stream = serverStream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.data(data(stream, FIRST_RECORD), Callback.NOOP);
            assertThat(receiverEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> resetWritten = resetAfter(
                    stream, sequence(SECOND_RECORD, new byte[]{(byte) 0xff}));
            resetWritten.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitCondition(() -> currentGeneration(transport) == null);
            releaseReceiver.countDown();

            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(SECOND_MESSAGE);
        } finally {
            releaseReceiver.countDown();
        }
    }

    @Test
    void dropsAcceptedControlBatchAfterExplicitCloseBeforeDelivery() throws Exception {
        CountDownLatch receiverEntered = new CountDownLatch(1);
        CountDownLatch releaseReceiver = new CountDownLatch(1);
        CompletableFuture<Stream> serverStream = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            serverStream.complete(stream);
            respond(stream, 200, false, () -> { });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            transport.onControlMessage(message -> {
                if (message.equals(FIRST_MESSAGE)) {
                    receiverEntered.countDown();
                    await(releaseReceiver);
                } else {
                    received.add(message);
                }
            });
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Stream stream = serverStream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.data(data(stream, FIRST_RECORD), Callback.NOOP);
            assertThat(receiverEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            stream.data(data(stream, sequence(SECOND_RECORD, new byte[]{(byte) 0xff})), Callback.NOOP);
            awaitCondition(() -> controlTerminalAccepted(transport));

            transport.close();
            releaseReceiver.countDown();

            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            releaseReceiver.countDown();
        }
    }

    @Test
    void ownsAnUnstartedTlsFactoryAcrossReconnectAndClose() throws Exception {
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> { });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            SslContextFactory.Client tls = peer.clientTls.get(0);
            assertThat(tls.isStarted()).isFalse();

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(tls.isStarted()).isTrue();
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(tls.isStarted()).isTrue();

            transport.close();
            transport.close();
            assertThat(tls.isStarted()).isFalse();
        }
    }

    @Test
    void leavesAnExternallyStartedTlsFactoryRunningOnClose() throws Exception {
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> { });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transportWithStartedTls(true);
            SslContextFactory.Client tls = peer.clientTls.get(0);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.close();

            assertThat(tls.isStarted()).isTrue();
        }
    }

    @Test
    void rejectsNonSuccessfulControlResponse() throws Exception {
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 401, true, () -> { });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);

            assertThatThrownBy(() -> transport.connect().toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(peer.requests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS).getHttpURI().getPath())
                    .isEqualTo("/agent/control");
        }
    }

    @Test
    void reportsCancelResetCompletesPendingSendsAndCanReconnect() throws Exception {
        AtomicInteger controls = new AtomicInteger();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            if (controls.incrementAndGet() == 1) {
                respond(stream, 200, false, () -> server.getScheduler().schedule(
                        () -> stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code),
                                Callback.NOOP), 100, TimeUnit.MILLISECONDS));
                return new Stream.Listener() { };
            }
            respond(stream, 200, false, () -> { });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxFrameBytes(256 * 1024);
            JettyHttp2Transport transport = peer.transport(true, limits, 8, 8);
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSignal(signals::add);
            byte[] largeRecord = byteString(128 * 1024);
            List<CompletableFuture<Void>> sends = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                sends.add(transport.sendControlCbor(largeRecord).toCompletableFuture());
            }

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            await(signals, TransportSignal.Kind.CONNECTED);
            assertThat(await(signals, TransportSignal.Kind.STREAM_RESET).sessionId()).isNull();

            assertThat(sends).allMatch(CompletableFuture::isDone);
            assertThat(sends).anyMatch(CompletableFuture::isCompletedExceptionally);
            assertThatThrownBy(() -> sends.get(sends.size() - 1).get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            await(signals, TransportSignal.Kind.CONNECTED);
            transport.sendControlCbor(FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsGoAwayAndReconnectsOnANewConnection() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> {
                if (first.getAndSet(false)) {
                    server.getScheduler().schedule(() -> stream.getSession().close(
                            ErrorCode.NO_ERROR.code, "test goaway", Callback.NOOP), 100, TimeUnit.MILLISECONDS);
                }
            });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSignal(signals::add);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            await(signals, TransportSignal.Kind.CONNECTED);
            await(signals, TransportSignal.Kind.GO_AWAY);

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            await(signals, TransportSignal.Kind.CONNECTED);
            transport.sendControlCbor(FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void replacementFailsOldConnectAndIgnoresItsStaleCallbacks() throws Exception {
        AtomicInteger controls = new AtomicInteger();
        CompletableFuture<Void> staleResponseCompleted = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            if (controls.incrementAndGet() == 1) {
                server.getScheduler().schedule(() -> {
                    MetaData.Response response = new MetaData.Response(
                            200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                    stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(
                            () -> staleResponseCompleted.complete(null),
                            failure -> staleResponseCompleted.complete(null)));
                }, 300, TimeUnit.MILLISECONDS);
            } else {
                respond(stream, 200, false, () -> { });
            }
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSignal(signals::add);

            CompletableFuture<Void> oldConnect = transport.connect().toCompletableFuture();
            assertThat(peer.requests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
            CompletableFuture<Void> replacement = transport.connect().toCompletableFuture();

            replacement.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThatThrownBy(() -> oldConnect.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            await(signals, TransportSignal.Kind.CONNECTED);
            staleResponseCompleted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.sendControlCbor(FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(signals).allSatisfy(signal -> assertThat(signal)
                    .extracting(TransportSignal::kind, value -> value.failure() == null
                            ? null : value.failure().toString())
                    .doesNotContain(TransportSignal.Kind.DISCONNECTED, TransportSignal.Kind.GO_AWAY));
        }
    }

    @Test
    void replacementDropsStaleControlItemsQueuedBehindAReceiver() throws Exception {
        AtomicInteger controls = new AtomicInteger();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> {
                if (controls.incrementAndGet() == 1) {
                    stream.data(data(stream, sequence(FIRST_RECORD, SECOND_RECORD)), Callback.NOOP);
                }
            });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean first = new AtomicBoolean(true);
            transport.onControlMessage(item -> {
                received.add(item);
                if (first.getAndSet(false)) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
            });
            try {
                transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

                transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                releaseFirst.countDown();

                assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(FIRST_MESSAGE);
                assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void sessionCancelResetIsIsolatedAndTheSameLogicalSessionCanReopen() throws Exception {
        SessionId resetSession = new SessionId("reset");
        SessionId healthySession = new SessionId("healthy");
        AtomicBoolean resetFirst = new AtomicBoolean(true);
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> {
                if (request.getHttpURI().getPath().equals(sessionPath(resetSession))
                        && resetFirst.getAndSet(false)) {
                    server.getScheduler().schedule(() -> stream.reset(
                            new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP),
                            100, TimeUnit.MILLISECONDS);
                }
            });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSignal(signals::add);
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            await(signals, TransportSignal.Kind.CONNECTED);
            transport.openSession(resetSession, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(healthySession, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            TransportSignal reset = await(signals, TransportSignal.Kind.STREAM_RESET);
            assertThat(reset.sessionId()).isEqualTo(resetSession);
            assertThat(reset.sessionId()).isNotEqualTo(healthySession);
            assertThatThrownBy(() -> transport.sendSessionCbor(resetSession, FIRST_RECORD)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            transport.sendSessionCbor(healthySession, FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.sendControlCbor(FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            transport.openSession(resetSession, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.sendSessionCbor(resetSession, SECOND_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void resetAndReopenDropsStaleSessionItemsQueuedBehindAReceiver() throws Exception {
        SessionId sessionId = new SessionId("reused");
        AtomicInteger sessions = new AtomicInteger();
        CompletableFuture<Stream> firstStream = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> {
                if (request.getHttpURI().getPath().equals(sessionPath(sessionId))) {
                    if (sessions.incrementAndGet() == 1) {
                        firstStream.complete(stream);
                        stream.data(data(stream, sequence(FIRST_RECORD, SECOND_RECORD)), Callback.NOOP);
                    } else {
                        stream.data(data(stream, THIRD_RECORD), Callback.NOOP);
                    }
                }
            });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<SessionItem> received = new LinkedBlockingQueue<>();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean first = new AtomicBoolean(true);
            transport.onSessionMessage((id, item) -> {
                received.add(new SessionItem(id, item));
                if (first.getAndSet(false)) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
            });
            try {
                transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                transport.openSession(sessionId, JettyHttp2LivePeerTest::sessionHeaders)
                        .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

                Stream stale = firstStream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                stale.reset(new ResetFrame(stale.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                openEventually(transport, sessionId);
                releaseFirst.countDown();

                SessionItem firstItem = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                SessionItem currentItem = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(firstItem).isNotNull();
                assertThat(currentItem).isNotNull();
                assertThat(firstItem.sessionId).isEqualTo(sessionId);
                assertThat(firstItem.message).isEqualTo(FIRST_MESSAGE);
                assertThat(currentItem.sessionId).isEqualTo(sessionId);
                assertThat(currentItem.message).isEqualTo(THIRD_MESSAGE);
                assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void rejectedSessionFailsItsOpenAndQueuedSendWithoutDisconnectingOthers() throws Exception {
        SessionId rejected = new SessionId("rejected");
        SessionId healthy = new SessionId("healthy");
        try (Peer peer = new Peer((server, stream, request, count) -> {
            if (request.getHttpURI().getPath().equals(sessionPath(rejected))) {
                server.getScheduler().schedule(
                        () -> respond(stream, 401, true, () -> { }), 100, TimeUnit.MILLISECONDS);
            } else {
                respond(stream, 200, false, () -> { });
            }
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSignal(signals::add);
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            await(signals, TransportSignal.Kind.CONNECTED);

            CompletableFuture<Void> rejectedOpen = transport
                    .openSession(rejected, JettyHttp2LivePeerTest::sessionHeaders).toCompletableFuture();
            CompletableFuture<Void> rejectedSend = transport.sendSessionCbor(rejected, FIRST_RECORD)
                    .toCompletableFuture();
            transport.openSession(healthy, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThatThrownBy(() -> rejectedOpen.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> rejectedSend.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(await(signals, TransportSignal.Kind.DISCONNECTED).sessionId()).isEqualTo(rejected);

            transport.sendSessionCbor(healthy, SECOND_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.sendControlCbor(FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void flowControlledSessionDoesNotBlockControlOrAnotherSession() throws Exception {
        SessionId stalled = new SessionId("stalled");
        SessionId healthy = new SessionId("healthy");
        CompletableFuture<Stream> stalledPeerStream = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> { });
            if (request.getHttpURI().getPath().equals(sessionPath(stalled))) {
                stalledPeerStream.complete(stream);
                return new Stream.Listener() { };
            }
            return Stream.Listener.AUTO_DISCARD;
        }, h2 -> h2.setInitialStreamRecvWindow(65_535))) {
            AgentProtocolLimits limits = AgentProtocolLimits.defaults().withMaxFrameBytes(256 * 1024);
            JettyHttp2Transport transport = peer.transport(true, limits, 8, 8);
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(stalled, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(healthy, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            CompletableFuture<Void> stalledSend = transport.sendSessionCbor(stalled, byteString(128 * 1024))
                    .toCompletableFuture();
            assertThatThrownBy(() -> stalledSend.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            transport.sendControlCbor(FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.sendSessionCbor(healthy, SECOND_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(stalledSend).isNotDone();

            Stream stream = stalledPeerStream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            assertThatThrownBy(() -> stalledSend.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void receivesIndependentTypedSessionMessagesAndRecreatesStreamsAfterReconnect() throws Exception {
        SessionId first = new SessionId("first");
        SessionId second = new SessionId("second");
        try (Peer peer = new Peer((server, stream, request, count) -> {
            String path = request.getHttpURI().getPath();
            if (path.equals(sessionPath(first))) {
                respond(stream, 200, false, () -> {
                    stream.data(data(stream, java.util.Arrays.copyOf(FIRST_RECORD, 2)), Callback.from(() ->
                            stream.data(data(stream, java.util.Arrays.copyOfRange(
                                    FIRST_RECORD, 2, FIRST_RECORD.length)), Callback.NOOP)));
                });
            } else if (path.equals(sessionPath(second))) {
                respond(stream, 200, false, () -> stream.data(
                        data(stream, sequence(SECOND_RECORD, FIRST_RECORD)), Callback.NOOP));
            } else {
                respond(stream, 200, false, () -> { });
            }
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<SessionItem> received = new LinkedBlockingQueue<>();
            transport.onSessionMessage((id, item) -> received.add(new SessionItem(id, item)));
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(first, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(second, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<SessionItem> initial = take(received, 3);
            assertThat(initial).anySatisfy(item -> {
                assertThat(item.sessionId).isEqualTo(first);
                assertThat(item.message).isEqualTo(FIRST_MESSAGE);
            }).anySatisfy(item -> {
                assertThat(item.sessionId).isEqualTo(second);
                assertThat(item.message).isEqualTo(SECOND_MESSAGE);
            }).anySatisfy(item -> {
                assertThat(item.sessionId).isEqualTo(second);
                assertThat(item.message).isEqualTo(FIRST_MESSAGE);
            });

            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThatThrownBy(() -> transport.sendSessionCbor(first, FIRST_RECORD)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            transport.openSession(first, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS).sessionId).isEqualTo(first);
            transport.sendSessionCbor(first, FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void skipsSemanticSessionFailureAndKeepsThatStreamUsable() throws Exception {
        SessionId sessionId = new SessionId("semantic-recovery");
        byte[] invalidKnown = {(byte) 0x81, 0x19, (byte) 0x80, 0x01};
        try (LogCapture logs = new LogCapture();
             Peer peer = new Peer((server, stream, request, count) -> {
                 respond(stream, 200, false, () -> {
                     if (request.getHttpURI().getPath().equals(sessionPath(sessionId))) {
                         byte[] rest = sequence(invalidKnown, SECOND_RECORD);
                         stream.data(data(stream, java.util.Arrays.copyOf(FIRST_RECORD, 2)), Callback.from(() ->
                                 stream.data(data(stream, sequence(
                                         java.util.Arrays.copyOfRange(FIRST_RECORD, 2, FIRST_RECORD.length),
                                         rest)), Callback.NOOP)));
                     }
                 });
                 return Stream.Listener.AUTO_DISCARD;
             })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<SessionItem> received = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSessionMessage((id, message) -> received.add(new SessionItem(id, message)));
            transport.onSignal(signals::add);
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(sessionId, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(take(received, 2)).extracting(SessionItem::message)
                    .containsExactly(FIRST_MESSAGE, SECOND_MESSAGE);
            assertThat(signals.stream()).noneMatch(signal -> signal.sessionId() != null);
            assertThat(logs.messages()).singleElement().asString()
                    .contains("stream=session", "sessionId=semantic-recovery", "itemLength=4");
        }
    }

    @Test
    void structuralSessionFailureDeliversPrefixAndLeavesOtherSessionUsable() throws Exception {
        SessionId damaged = new SessionId("damaged");
        SessionId healthy = new SessionId("healthy-structural");
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> {
                String path = request.getHttpURI().getPath();
                if (path.equals(sessionPath(damaged))) {
                    stream.data(data(stream, sequence(
                            FIRST_RECORD, new byte[]{(byte) 0xff}, SECOND_RECORD)), Callback.NOOP);
                } else if (path.equals(sessionPath(healthy))) {
                    stream.data(data(stream, THIRD_RECORD), Callback.NOOP);
                }
            });
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<SessionItem> received = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<TransportSignal> signals = new LinkedBlockingQueue<>();
            transport.onSessionMessage((id, message) -> received.add(new SessionItem(id, message)));
            transport.onSignal(signals::add);
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(damaged, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(healthy, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<SessionItem> items = take(received, 2);
            assertThat(items).anySatisfy(item -> {
                assertThat(item.sessionId()).isEqualTo(damaged);
                assertThat(item.message()).isEqualTo(FIRST_MESSAGE);
            }).anySatisfy(item -> {
                assertThat(item.sessionId()).isEqualTo(healthy);
                assertThat(item.message()).isEqualTo(THIRD_MESSAGE);
            });
            assertThat(await(signals, TransportSignal.Kind.DISCONNECTED).sessionId()).isEqualTo(damaged);
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
            transport.sendSessionCbor(healthy, FIRST_RECORD).toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void keepsAcceptedSessionPrefixWhenResetInvalidatesStreamBeforeDelivery() throws Exception {
        SessionId sessionId = new SessionId("queued-prefix");
        CountDownLatch receiverEntered = new CountDownLatch(1);
        CountDownLatch releaseReceiver = new CountDownLatch(1);
        CompletableFuture<Stream> serverStream = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> { });
            if (request.getHttpURI().getPath().equals(sessionPath(sessionId))) {
                serverStream.complete(stream);
            }
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            transport.onSessionMessage((id, message) -> {
                if (message.equals(FIRST_MESSAGE)) {
                    receiverEntered.countDown();
                    await(releaseReceiver);
                } else {
                    received.add(message);
                }
            });
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(sessionId, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Stream stream = serverStream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.data(data(stream, FIRST_RECORD), Callback.NOOP);
            assertThat(receiverEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> resetWritten = resetAfter(
                    stream, sequence(SECOND_RECORD, new byte[]{(byte) 0xff}));
            resetWritten.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitCondition(() -> !sessionIsCurrent(transport, sessionId));
            releaseReceiver.countDown();

            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(SECOND_MESSAGE);
        } finally {
            releaseReceiver.countDown();
        }
    }

    @Test
    void dropsAcceptedSessionBatchAfterExplicitCloseBeforeDelivery() throws Exception {
        SessionId sessionId = new SessionId("closed-prefix");
        CountDownLatch receiverEntered = new CountDownLatch(1);
        CountDownLatch releaseReceiver = new CountDownLatch(1);
        CompletableFuture<Stream> serverStream = new CompletableFuture<>();
        try (Peer peer = new Peer((server, stream, request, count) -> {
            respond(stream, 200, false, () -> { });
            if (request.getHttpURI().getPath().equals(sessionPath(sessionId))) {
                serverStream.complete(stream);
            }
            return Stream.Listener.AUTO_DISCARD;
        })) {
            JettyHttp2Transport transport = peer.transport(true);
            LinkedBlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();
            transport.onSessionMessage((id, message) -> {
                if (message.equals(FIRST_MESSAGE)) {
                    receiverEntered.countDown();
                    await(releaseReceiver);
                } else {
                    received.add(message);
                }
            });
            transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            transport.openSession(sessionId, JettyHttp2LivePeerTest::sessionHeaders)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Stream stream = serverStream.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.data(data(stream, FIRST_RECORD), Callback.NOOP);
            assertThat(receiverEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            stream.data(data(stream, sequence(SECOND_RECORD, new byte[]{(byte) 0xff})), Callback.NOOP);
            awaitCondition(() -> sessionTerminalAccepted(transport, sessionId));

            transport.close();
            releaseReceiver.countDown();

            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            releaseReceiver.countDown();
        }
    }

    @Test
    void failsUntrustedTlsWithoutLeakingClientResources() throws Exception {
        try (Peer peer = new Peer((server, stream, request, count) -> Stream.Listener.AUTO_DISCARD)) {
            JettyHttp2Transport transport = peer.transport(false);

            assertThatThrownBy(() -> transport.connect().toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void failsWhenThePeerDoesNotNegotiateHttp2() throws Exception {
        try (Http1Peer peer = new Http1Peer()) {
            JettyHttp2Transport transport = peer.transport();

            assertThatThrownBy(() -> transport.connect().toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    private static List<SessionItem> take(LinkedBlockingQueue<SessionItem> queue, int count)
            throws InterruptedException {
        List<SessionItem> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SessionItem item = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(item).isNotNull();
            result.add(item);
        }
        return result;
    }

    private static void openEventually(JettyHttp2Transport transport, SessionId sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                transport.openSession(sessionId, JettyHttp2LivePeerTest::sessionHeaders)
                        .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return;
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof IllegalStateException)
                        || !"session stream is already open".equals(failure.getCause().getMessage())) {
                    throw failure;
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("timed out reopening " + sessionId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for callback release");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for callback release", failure);
        }
    }

    private static CompletableFuture<Void> resetAfter(Stream stream, byte[] bytes) {
        CompletableFuture<Void> reset = new CompletableFuture<>();
        stream.data(data(stream, bytes), Callback.from(
                () -> stream.reset(
                        new ResetFrame(stream.getId(), ErrorCode.PROTOCOL_ERROR.code),
                        Callback.from(() -> reset.complete(null), reset::completeExceptionally)),
                reset::completeExceptionally));
        return reset;
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static Object currentGeneration(JettyHttp2Transport transport) {
        synchronized (transport) {
            return field(JettyHttp2Transport.class, "current", transport);
        }
    }

    private static boolean sessionIsCurrent(JettyHttp2Transport transport, SessionId sessionId) {
        Object generation = currentGeneration(transport);
        if (generation == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<SessionId, ?> sessions = (Map<SessionId, ?>) field(generation.getClass(), "sessions", generation);
        return sessions.containsKey(sessionId);
    }

    private static boolean controlTerminalAccepted(JettyHttp2Transport transport) {
        Object generation = currentGeneration(transport);
        return generation != null && (boolean) field(generation.getClass(), "terminalAccepted", generation);
    }

    private static boolean sessionTerminalAccepted(JettyHttp2Transport transport, SessionId sessionId) {
        Object generation = currentGeneration(transport);
        if (generation == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<SessionId, ?> sessions = (Map<SessionId, ?>) field(generation.getClass(), "sessions", generation);
        Object state = sessions.get(sessionId);
        return state != null && (boolean) field(state.getClass(), "terminalAccepted", state);
    }

    private static Object field(Class<?> owner, String name, Object target) {
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not inspect transport state", failure);
        }
    }

    private static TransportSignal await(LinkedBlockingQueue<TransportSignal> signals,
                                         TransportSignal.Kind kind) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        List<String> received = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            TransportSignal signal = signals.poll(Math.max(1, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
            if (signal == null) {
                break;
            }
            if (signal.kind() == kind) {
                return signal;
            }
            received.add(signal.kind() + ": " + signal.failure());
        }
        throw new AssertionError("timed out waiting for " + kind + "; received " + received);
    }

    private static byte[] byteString(int payloadBytes) {
        byte[] item = new byte[payloadBytes + 5];
        item[0] = 0x5a;
        item[1] = (byte) (payloadBytes >>> 24);
        item[2] = (byte) (payloadBytes >>> 16);
        item[3] = (byte) (payloadBytes >>> 8);
        item[4] = (byte) payloadBytes;
        return item;
    }

    private static HeadersFrame sessionHeaders(SessionId sessionId) {
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from(sessionPath(sessionId)),
                HttpVersion.HTTP_2, HttpFields.EMPTY);
        return new HeadersFrame(request, null, false);
    }

    private static String sessionPath(SessionId sessionId) {
        return "/agent/session/" + sessionId.value();
    }

    private static DataFrame data(Stream stream, byte[] bytes) {
        return new DataFrame(stream.getId(), ByteBuffer.wrap(bytes), false);
    }

    private static byte[] sequence(byte[]... items) {
        int length = 0;
        for (byte[] item : items) {
            length += item.length;
        }
        byte[] result = new byte[length];
        int position = 0;
        for (byte[] item : items) {
            System.arraycopy(item, 0, result, position, item.length);
            position += item.length;
        }
        return result;
    }

    private static void respond(Stream stream, int status, boolean endStream, Runnable accepted) {
        MetaData.Response response = new MetaData.Response(status, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
        stream.headers(new HeadersFrame(stream.getId(), response, null, endStream), Callback.from(accepted));
    }

    private static SslContextFactory.Server serverTls(KeyStore keys) {
        SslContextFactory.Server tls = new SslContextFactory.Server();
        tls.setKeyStore(keys);
        tls.setKeyManagerPassword("changeit");
        tls.setCertAlias("test");
        return tls;
    }

    private static KeyStore keys() {
        try {
            CertUtils.PrivateKeyWithCerts certificate = CertUtils.generateSelfSignedCertificate();
            return CertUtils.convertToKeyStore(certificate, "test", "changeit".toCharArray());
        } catch (Exception failure) {
            throw new IllegalStateException("could not create peer certificate", failure);
        }
    }

    private static byte[] encode(AgentMessage message) {
        try {
            return CODEC.encode(message);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record SessionItem(SessionId sessionId, AgentMessage message) { }

    private static final class LogCapture extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(JettyHttp2Transport.class.getName());
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private LogCapture() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(java.text.MessageFormat.format(record.getMessage(), record.getParameters()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }

        private List<String> messages() {
            return List.copyOf(messages);
        }
    }

    private static final class Peer implements AutoCloseable {
        private final Server server = new Server();
        private final KeyStore keys = keys();
        private final ServerConnector connector;
        private final LinkedBlockingQueue<MetaData.Request> requests = new LinkedBlockingQueue<>();
        private final List<JettyHttp2Transport> transports = new CopyOnWriteArrayList<>();
        private final List<SslContextFactory.Client> clientTls = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Throwable> peerFailure = new CompletableFuture<>();
        private final AtomicInteger streamCount = new AtomicInteger();

        private Peer(Responder responder) throws Exception {
            this(responder, ignored -> { });
        }

        private Peer(Responder responder, Consumer<HTTP2ServerConnectionFactory> configure) throws Exception {
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory() {
                @Override
                protected ServerSessionListener newSessionListener(Connector ignored, EndPoint endPoint) {
                    return new ServerSessionListener() {
                        @Override
                        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                            try {
                                MetaData.Request request = (MetaData.Request) frame.getMetaData();
                                requests.add(request);
                                return responder.respond(
                                        server, stream, request, streamCount.incrementAndGet());
                            } catch (Throwable failure) {
                                peerFailure.complete(failure);
                                return Stream.Listener.AUTO_DISCARD;
                            }
                        }
                    };
                }
            };
            configure.accept(h2);
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
            alpn.setDefaultProtocol("h2");
            connector = new ServerConnector(server,
                    new SslConnectionFactory(serverTls(keys), alpn.getProtocol()), alpn, h2);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            try {
                server.start();
            } catch (Exception failure) {
                server.stop();
                throw failure;
            }
        }

        private JettyHttp2Transport transport(boolean trust) throws Exception {
            return transport(trust, AgentProtocolLimits.defaults(), 8, 8);
        }

        private JettyHttp2Transport transport(boolean trust, AgentProtocolLimits limits,
                                              int controlCapacity, int sessionCapacity) throws Exception {
            return transport(trust, limits, controlCapacity, sessionCapacity, false);
        }

        private JettyHttp2Transport transportWithStartedTls(boolean trust) throws Exception {
            return transport(trust, AgentProtocolLimits.defaults(), 8, 8, true);
        }

        private JettyHttp2Transport transport(boolean trust, AgentProtocolLimits limits,
                                              int controlCapacity, int sessionCapacity,
                                              boolean startTls) throws Exception {
            SslContextFactory.Client tls = new SslContextFactory.Client();
            if (trust) {
                tls.setTrustStore(keys);
            }
            if (startTls) {
                tls.start();
            }
            clientTls.add(tls);
            JettyHttp2Transport transport = new JettyHttp2Transport(
                    URI.create("https://localhost:" + connector.getLocalPort()), tls, limits,
                    controlCapacity, sessionCapacity);
            transports.add(transport);
            return transport;
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            for (JettyHttp2Transport transport : transports) {
                try {
                    transport.close();
                } catch (Throwable closeFailure) {
                    failure = addFailure(failure, closeFailure);
                }
            }
            for (SslContextFactory.Client tls : clientTls) {
                try {
                    if (!tls.isStopped()) {
                        tls.stop();
                    }
                } catch (Throwable closeFailure) {
                    failure = addFailure(failure, closeFailure);
                }
            }
            try {
                server.stop();
                server.join();
            } catch (Throwable closeFailure) {
                failure = addFailure(failure, closeFailure);
            }
            if (peerFailure.isDone()) {
                failure = addFailure(failure, peerFailure.getNow(null));
            }
            rethrow(failure);
        }
    }

    private static final class Http1Peer implements AutoCloseable {
        private final Server server = new Server();
        private final KeyStore keys = keys();
        private final ServerConnector connector;
        private final SslContextFactory.Client clientTls = new SslContextFactory.Client();
        private JettyHttp2Transport transport;

        private Http1Peer() throws Exception {
            HttpConnectionFactory http1 = new HttpConnectionFactory(new HttpConfiguration());
            connector = new ServerConnector(server,
                    new SslConnectionFactory(serverTls(keys), HttpVersion.HTTP_1_1.asString()), http1);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            server.start();
            clientTls.setTrustStore(keys);
            clientTls.start();
        }

        private JettyHttp2Transport transport() {
            transport = new JettyHttp2Transport(URI.create("https://localhost:" + connector.getLocalPort()),
                    clientTls, AgentProtocolLimits.defaults(), 2, 2);
            return transport;
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            try {
                if (transport != null) {
                    transport.close();
                }
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                clientTls.stop();
            } catch (Throwable closeFailure) {
                failure = addFailure(failure, closeFailure);
            }
            try {
                server.stop();
                server.join();
            } catch (Throwable closeFailure) {
                failure = addFailure(failure, closeFailure);
            }
            rethrow(failure);
        }
    }

    private static Throwable addFailure(Throwable existing, Throwable added) {
        if (existing == null) {
            return added;
        }
        if (added != null && added != existing) {
            existing.addSuppressed(added);
        }
        return existing;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }

    @FunctionalInterface
    private interface Responder {
        Stream.Listener respond(Server server, Stream stream, MetaData.Request request, int count);
    }
}
