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
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.transport.JettyHttp2Transport;
import pro.deta.orion.util.CertUtils;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControlLivePeerTest {
    @Test
    void negotiatesAuthenticatedHandshakeAcrossRealHttp2Transport() throws Exception {
        AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
        CompletableFuture<AgentMessage.Hello> received = new CompletableFuture<>();
        try (Peer peer = new Peer(codec, received, false)) {
            AgentLaunchContext context = AgentHandshakeTest.context();
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), context, "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of());

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
        try (Peer peer = new Peer(codec, received, true)) {
            AgentControlService service = new AgentControlService(
                    peer.transport(), codec, new AgentHandshake(), AgentHandshakeTest.context(), "1.0.0",
                    new MachineInfo("runner", "linux", "aarch64"), Map.of());

            service.start();

            assertThat(received.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(service.connection()).isPresent();
            service.close();
        }
    }

    private static final class Peer implements AutoCloseable {
        private final Server server = new Server();
        private final KeyStore keys = keys();
        private final SslContextFactory.Client clientTls = new SslContextFactory.Client();
        private final ServerConnector connector;
        private final AgentProtocolCodec codec;
        private final CompletableFuture<AgentMessage.Hello> received;
        private final boolean prependSemanticFailure;
        private MetaData.Request request;

        private Peer(
                AgentProtocolCodec codec,
                CompletableFuture<AgentMessage.Hello> received,
                boolean prependSemanticFailure
        ) throws Exception {
            this.codec = codec;
            this.received = received;
            this.prependSemanticFailure = prependSemanticFailure;
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory() {
                @Override
                protected ServerSessionListener newSessionListener(Connector ignored, EndPoint endPoint) {
                    return new ServerSessionListener() {
                        @Override
                        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
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
                        received.complete((AgentMessage.Hello) codec.decode(item));
                        AgentMessage.Welcome welcome = AgentHandshakeTest.welcome("connection-live", (byte) 9);
                        byte[] encoded = codec.encode(welcome);
                        if (prependSemanticFailure) {
                            byte[] invalidWelcome = {(byte) 0x81, 0x19, (byte) 0x80, 0x01};
                            stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(invalidWelcome), false),
                                    Callback.from(() -> stream.data(new DataFrame(
                                            stream.getId(), ByteBuffer.wrap(encoded), false), Callback.NOOP)));
                        } else {
                            stream.data(
                                    new DataFrame(stream.getId(), ByteBuffer.wrap(encoded), false), Callback.NOOP);
                        }
                    } catch (Exception failure) {
                        received.completeExceptionally(failure);
                    } finally {
                        data.release();
                    }
                }
                stream.demand();
            }
        }
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
