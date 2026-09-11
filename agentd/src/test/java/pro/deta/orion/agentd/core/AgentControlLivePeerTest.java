package pro.deta.orion.agentd.core;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.transport.JettyHttp2Transport;
import pro.deta.orion.util.CertUtils;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentControlLivePeerTest {
    @Test
    void negotiatesAuthenticatedHandshakeAcrossRealHttp2Transport() throws Exception {
        AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
        CompletableFuture<AgentMessage.Hello> received = new CompletableFuture<>();
        try (Peer peer = new Peer(codec, received, Reply.SUPPORTED, Integer.MAX_VALUE)) {
            AgentLaunchContext context = AgentHandshakeTest.context();
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), context, "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of(), new SessionRegistry());

            service.start();

            AgentMessage.Hello hello = received.get(5, TimeUnit.SECONDS);
            assertThat(hello.authentication()).isPresent();
            assertThat(peer.request.getHttpURI().getPath()).isEqualTo("/agent/control");
            assertThat(peer.request.getHttpURI().toString()).doesNotContain("BwcHBwcH");
            assertThat(peer.request.getHttpFields().toString()).doesNotContain("BwcHBwcH");
            assertThat(service.connection()).isPresent();
            service.close();
        }
    }

    @Test
    void negotiatesAfterAFragmentedSemanticFailure() throws Exception {
        AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
        CompletableFuture<AgentMessage.Hello> received = new CompletableFuture<>();
        try (Peer peer = new Peer(codec, received, Reply.SEMANTIC_THEN_SUPPORTED, 4)) {
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), AgentHandshakeTest.context(), "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of(), new SessionRegistry());

            service.start();

            assertThat(received.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(service.connection()).isPresent();
            service.close();
        }
    }

    @Test
    void reconnectsAcrossRealHttp2ConnectionsWithTheServerToken() throws Exception {
        AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
        CompletableFuture<AgentMessage.Hello> received = new CompletableFuture<>();
        try (Peer peer = new Peer(codec, received, Reply.SUPPORTED, Integer.MAX_VALUE)) {
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), AgentHandshakeTest.context(), "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of(), new SessionRegistry());

            service.start();
            peer.disconnectControl();

            peer.awaitHellos(2);
            AgentMessage.Hello reconnect = peer.hellos.get(1);
            assertThat(reconnect.authentication()).hasValueSatisfying(authentication -> {
                assertThat(authentication.kind()).isEqualTo(AgentAuthentication.Kind.RECONNECT_TOKEN);
                assertThat(authentication.credential().toByteArray()).containsOnly(9);
            });
            assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                    .isEqualTo(new ConnectionId("connection-live-2"));
            service.close();
        }
    }

    @Test
    void rejectsUnsupportedWelcomeFromRealHttp2Transport() throws Exception {
        AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
        CompletableFuture<AgentMessage.Hello> received = new CompletableFuture<>();
        try (Peer peer = new Peer(codec, received, Reply.UNSUPPORTED, Integer.MAX_VALUE)) {
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), AgentHandshakeTest.context(), "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of(),
                    new SessionRegistry(), Duration.ofSeconds(1));

            assertThatExceptionOfType(HandshakeException.class)
                    .isThrownBy(service::start)
                    .withMessageContaining("unsupported")
                    .withCauseInstanceOf(AgentProtocolException.class);
            assertThat(received.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(service.connection()).isEmpty();
            service.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4096})
    void rejectsUnsupportedWelcomeBeforeSupportedWelcomeAcrossChunkings(int chunkSize) throws Exception {
        AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
        CompletableFuture<AgentMessage.Hello> received = new CompletableFuture<>();
        try (Peer peer = new Peer(codec, received, Reply.UNSUPPORTED_THEN_SUPPORTED, chunkSize)) {
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), AgentHandshakeTest.context(), "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of(),
                    new SessionRegistry(), Duration.ofSeconds(1));

            assertThatExceptionOfType(HandshakeException.class)
                    .isThrownBy(service::start)
                    .withMessageContaining("unsupported")
                    .withCauseInstanceOf(AgentProtocolException.class);
            assertThat(received.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(service.connection()).isEmpty();
            service.close();
        }
    }

    private enum Reply {
        SUPPORTED,
        SEMANTIC_THEN_SUPPORTED,
        UNSUPPORTED,
        UNSUPPORTED_THEN_SUPPORTED
    }

    private static final class Peer implements AutoCloseable {
        private final Server server = new Server();
        private final KeyStore keys = keys();
        private final SslContextFactory.Client clientTls = new SslContextFactory.Client();
        private final ServerConnector connector;
        private final AgentProtocolCodec codec;
        private final CompletableFuture<AgentMessage.Hello> received;
        private final Reply reply;
        private final int responseChunkSize;
        private final List<AgentMessage.Hello> hellos = new CopyOnWriteArrayList<>();
        private final List<Stream> streams = new CopyOnWriteArrayList<>();
        private MetaData.Request request;

        private Peer(
                AgentProtocolCodec codec,
                CompletableFuture<AgentMessage.Hello> received,
                Reply reply,
                int responseChunkSize
        ) throws Exception {
            this.codec = codec;
            this.received = received;
            this.reply = reply;
            this.responseChunkSize = responseChunkSize;
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory() {
                @Override
                protected ServerSessionListener newSessionListener(Connector ignored, EndPoint endPoint) {
                    return new ServerSessionListener() {
                        @Override
                        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                            streams.add(stream);
                            request = (MetaData.Request) frame.getMetaData();
                            MetaData.Response response = new MetaData.Response(
                                    200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                            stream.headers(
                                    new HeadersFrame(stream.getId(), response, null, false), Callback.NOOP);
                            stream.demand();
                            return new ControlListener(stream);
                        }
                    };
                }
            };
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
            alpn.setDefaultProtocol("h2");
            connector = new ServerConnector(server,
                    new SslConnectionFactory(serverTls(keys), alpn.getProtocol()), alpn, h2);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            server.start();
            clientTls.setTrustStore(keys);
        }

        private JettyHttp2Transport transport() {
            return new JettyHttp2Transport(
                    URI.create("https://localhost:" + connector.getLocalPort()), clientTls,
                    AgentProtocolLimits.defaults(), 8, 8);
        }

        private void disconnectControl() {
            Stream stream = streams.getFirst();
            stream.reset(new org.eclipse.jetty.http2.frames.ResetFrame(
                    stream.getId(), org.eclipse.jetty.http2.ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        }

        private void awaitHellos(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hellos.size() < count && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5);
            }
            assertThat(hellos).hasSize(count);
        }

        @Override
        public void close() throws Exception {
            if (!clientTls.isStopped()) {
                clientTls.stop();
            }
            server.stop();
            server.join();
        }

        private final class ControlListener implements Stream.Listener {
            private final Stream stream;

            private ControlListener(Stream stream) {
                this.stream = stream;
            }

            @Override
            public void onDataAvailable(Stream ignored) {
                Stream.Data data;
                while ((data = stream.readData()) != null) {
                    try {
                        byte[] item = new byte[data.frame().getByteBuffer().remaining()];
                        data.frame().getByteBuffer().get(item);
                        AgentMessage decoded = codec.decode(item);
                        if (!(decoded instanceof AgentMessage.Hello hello)) {
                            continue;
                        }
                        hellos.add(hello);
                        received.complete(hello);
                        AgentMessage.Welcome welcome = AgentHandshakeTest.welcome(
                                "connection-live-" + hellos.size(), (byte) (8 + hellos.size()));
                        byte[] supported = codec.encode(welcome);
                        byte[] unsupported = supported.clone();
                        unsupported[4] = 2;
                        byte[] response = switch (reply) {
                            case SUPPORTED -> supported;
                            case SEMANTIC_THEN_SUPPORTED -> sequence(
                                    new byte[]{(byte) 0x81, 0x19, (byte) 0x80, 0x01}, supported);
                            case UNSUPPORTED -> unsupported;
                            case UNSUPPORTED_THEN_SUPPORTED -> sequence(unsupported, supported);
                        };
                        send(response, 0);
                    } catch (Exception failure) {
                        received.completeExceptionally(failure);
                    } finally {
                        data.release();
                    }
                }
                stream.demand();
            }

            private void send(byte[] response, int offset) {
                int length = Math.min(responseChunkSize, response.length - offset);
                byte[] chunk = Arrays.copyOfRange(response, offset, offset + length);
                Callback callback = offset + length == response.length
                        ? Callback.NOOP
                        : Callback.from(() -> send(response, offset + length));
                stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(chunk), false), callback);
            }
        }
    }

    private static byte[] sequence(byte[] first, byte[] second) {
        byte[] sequence = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, sequence, first.length, second.length);
        return sequence;
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
}
