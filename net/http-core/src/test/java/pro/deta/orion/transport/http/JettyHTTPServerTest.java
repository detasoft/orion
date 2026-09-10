package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolDecoder;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.AcmeKeyMaterial;
import pro.deta.orion.keymaterial.AcmeMaterialConfiguration;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;
import pro.deta.orion.util.NetworkUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JettyHTTPServerTest {
    private static final AgentProtocolCodec AGENT_CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());
    private static final int HTTPS_START_ATTEMPTS = 3;
    private static final String CLUSTER = "test-cluster";
    private static final KeyMaterialDescriptor SIGNING = descriptor(
            "server-signing-v1", KeyMaterialPurpose.SERVER_SIGNING);
    private static final KeyMaterialDescriptor ACCOUNT = descriptor(
            "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT);
    private static final KeyMaterialDescriptor IDENTITY = descriptor(
            "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY);
    private static final TrustedCertificateDescriptor SERVER_ROOT = trusted("server-root-v1");
    private static final TrustedCertificateDescriptor CLIENT_ROOT = trusted("client-root-v1");
    private static final SharedMaterial SHARED_MATERIAL = sharedMaterial();

    @Test
    void servesMaterialBackedHttpsWithoutRootInTheServerChain() throws Exception {
        try (MaterialFixture material = material()) {
            JettyHTTPServer server = startHttps(
                    material,
                    true,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    new OkRoute());

            try {
                try (var http = closeable(
                        (HttpURLConnection) server.relativiseHttp("/ok").openConnection())) {
                    assertThat(http.value().getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
                }

                try (var https = closeable(httpsConnection(server, clientContext(null, null)))) {
                    assertThat(https.value().getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
                    assertThat(https.value().getServerCertificates())
                            .extracting(Certificate::getPublicKey)
                            .containsExactly(material.serverCertificate().getPublicKey());
                }
            } finally {
                server.onStop();
            }
        }
    }

    @Test
    void appliesDisabledWantAndRequiredClientAuthenticationWithRoleSeparatedRoots() throws Exception {
        try (MaterialFixture material = material()) {
            SSLContext anonymous = clientContext(null, null);
            SSLContext trusted = clientContext(material.trustedClientKey(), material.trustedClientCertificate());
            SSLContext serverIssuerClient = clientContext(
                    material.serverIssuerClientKey(), material.serverIssuerClientCertificate());

            assertHttpsSucceeds(material, OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), anonymous);
            assertHttpsSucceeds(material, OrionHttpsConfiguration.ClientAuthentication.WANT, List.of(CLIENT_ROOT), anonymous);
            assertHttpsSucceeds(material, OrionHttpsConfiguration.ClientAuthentication.WANT, List.of(CLIENT_ROOT), trusted);
            assertHttpsFails(material, OrionHttpsConfiguration.ClientAuthentication.REQUIRED, List.of(CLIENT_ROOT), anonymous);
            assertHttpsSucceeds(
                    material,
                    OrionHttpsConfiguration.ClientAuthentication.REQUIRED,
                    List.of(CLIENT_ROOT),
                    trusted);
            assertHttpsFails(
                    material,
                    OrionHttpsConfiguration.ClientAuthentication.REQUIRED,
                    List.of(CLIENT_ROOT),
                    serverIssuerClient);
            assertHttpsSucceeds(
                    material,
                    OrionHttpsConfiguration.ClientAuthentication.REQUIRED,
                    List.of(CLIENT_ROOT, SERVER_ROOT),
                    serverIssuerClient);
        }
    }

    @Test
    void failsHttpsStartupForStorageOnlyIdentity() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            AcmeMaterialConfiguration acme = new AcmeMaterialConfiguration(
                    ACCOUNT, IDENTITY, Optional.empty());
            owner.acme().acquire(acme, 2048, 2048);
            JettyHTTPServer server = server(
                    httpConfiguration(false),
                    desiredState(OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of()),
                    owner.tls(),
                    new OkRoute());

            assertThatThrownBy(server::onStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start Jetty HTTP server");
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    void retriesHttpsFixtureAfterBindCollision() throws Exception {
        AtomicInteger selections = new AtomicInteger();
        try (MaterialFixture material = material();
             ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    () -> selections.getAndIncrement() == 0
                            ? occupied.getLocalPort()
                            : NetworkUtils.findAvailablePort(),
                    new OkRoute());

            try {
                assertThat(selections).hasValue(2);
                try (var connection = closeable(httpsConnection(server, clientContext(null, null)))) {
                    assertThat(connection.value().getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
                }
            } finally {
                server.onStop();
            }
        }
    }

    @Test
    void failsStartupWhenHttpPortIsAlreadyInUse() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            OrionConfiguration bootstrap = httpConfiguration(false);
            bootstrap.getTransport().setHttp(new HttpTransportConfig("127.0.0.1", occupied.getLocalPort()));
            JettyHTTPServer server = server(
                    bootstrap, desiredStateWithoutHttps(), TlsCapability.unavailable(), new OkRoute());

            assertThatThrownBy(server::onStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start Jetty HTTP server");
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    void appliesConfiguredHttpBacklog() {
        OrionConfiguration bootstrap = httpConfiguration(true);
        bootstrap.getTransport().getHttp().setBacklog(37);
        JettyHTTPServer server = server(
                bootstrap, desiredStateWithoutHttps(), TlsCapability.unavailable(), new OkRoute());
        server.onStart();

        try {
            assertThat(server.getJettyServer().get().getConnectors()).hasSize(1);
            ServerConnector connector =
                    (ServerConnector) server.getJettyServer().get().getConnectors()[0];
            assertThat(connector.getAcceptQueueSize()).isEqualTo(37);
        } finally {
            server.onStop();
        }
    }

    @Test
    void stopClearsServerReferenceAfterGracefulShutdownTimeout() throws Exception {
        OrionConfiguration bootstrap = httpConfiguration(true);
        BlockingRoute route = new BlockingRoute();
        JettyHTTPServer server = server(
                bootstrap, desiredStateWithoutHttps(), TlsCapability.unavailable(), route);
        ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
        server.onStart();

        URL blockUrl = server.relativiseHttp("/block");
        Future<?> clientRequest = clientExecutor.submit(() -> request(blockUrl));
        assertThat(route.awaitStarted()).isTrue();

        try {
            server.onStop();
            assertThat(server.getJettyServer().get()).isNull();
            assertThat(server.isRunning()).isFalse();
        } finally {
            route.release();
            clientRequest.cancel(true);
            clientExecutor.shutdownNow();
        }
    }

    @Test
    void compressesApplicationJavaScriptResponses() throws Exception {
        JettyHTTPServer server = server(
                httpConfiguration(true),
                desiredStateWithoutHttps(),
                TlsCapability.unavailable(),
                new JavascriptRoute());
        server.onStart();

        try {
            URL url = new URL("http://127.0.0.1:" + server.boundHttpPort() + "/app.js");
            try (var connection = closeable((HttpURLConnection) url.openConnection())) {
                connection.value().setRequestProperty("Accept-Encoding", "gzip");
                assertThat(connection.value().getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
                assertThat(connection.value().getHeaderField("Content-Encoding")).isEqualTo("gzip");
                try (var response = connection.value().getInputStream()) {
                    assertThat(response.readAllBytes()).startsWith((byte) 0x1f, (byte) 0x8b);
                }
            }
        } finally {
            server.onStop();
        }
    }

    @Test
    void servesBidirectionalAgentControlOverHttp2BeforeRequestBodyArrives() throws Exception {
        List<AgentMessage> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> handshakeCompleted = new CompletableFuture<>();
        AgentMessage firstReply = new AgentMessage.RequestSessionList();
        AgentMessage secondReply = new AgentMessage.SessionSync(new SessionId("reply"), Optional.empty());
        AgentControlHandler handler = connection -> {
            return new AgentControlHandler.Session() {
                @Override
                public void onMessage(AgentMessage message) {
                    received.add(message);
                    if (received.size() == 2) {
                        connection.send(firstReply);
                        connection.send(secondReply).thenRun(() -> {
                            connection.handshakeComplete();
                            handshakeCompleted.complete(null);
                        });
                    } else if (received.size() == 3) {
                        connection.send(firstReply);
                    }
                }

                @Override
                public void onClosed(Throwable failure) {
                }
            };
        };
        try (MaterialFixture material = material()) {
            AgentControlRoute control = new AgentControlRoute(
                    handler, AgentProtocolLimits.defaults(), Duration.ofMillis(200));
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    control,
                    new OkRoute());
            try (TestAgentClient client = agentClient(server, material.serverCertificate())) {
                byte[] first = AGENT_CODEC.encode(new AgentMessage.RequestSessionList());
                byte[] second = AGENT_CODEC.encode(
                        new AgentMessage.SessionSync(new SessionId("incoming"), Optional.empty()));
                client.connect();

                byte[] input = concatenate(first, second);
                client.send(new byte[]{0x01});
                client.send(java.util.Arrays.copyOfRange(input, 0, 1));
                client.send(java.util.Arrays.copyOfRange(input, 1, input.length));
                awaitMessages(received, 2);
                assertThat(received).containsExactly(
                        new AgentMessage.RequestSessionList(),
                        new AgentMessage.SessionSync(new SessionId("incoming"), Optional.empty()));
                assertThat(client.replies.poll(5, TimeUnit.SECONDS)).isEqualTo(firstReply);
                assertThat(client.replies.poll(5, TimeUnit.SECONDS)).isEqualTo(secondReply);
                handshakeCompleted.get(5, TimeUnit.SECONDS);

                Thread.sleep(400);
                client.send(first);
                awaitMessages(received, 3);
                assertThat(client.replies.poll(5, TimeUnit.SECONDS)).isEqualTo(firstReply);
            } finally {
                server.onStop();
            }
        }
    }

    @Test
    void rejectsProductionControlAndMalformedInputAfterSuccessfulHeaders() throws Exception {
        try (MaterialFixture material = material()) {
            AgentControlRoute production = new AgentControlRoute();
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    production);
            try (TestAgentClient client = agentClient(server, material.serverCertificate())) {
                client.connect();
                client.send(AGENT_CODEC.encode(new AgentMessage.RequestSessionList()));
                client.terminal.get(5, TimeUnit.SECONDS);
            } finally {
                server.onStop();
            }

            CompletableFuture<Throwable> closed = new CompletableFuture<>();
            AgentControlHandler handler = connection -> new AgentControlHandler.Session() {
                @Override
                public void onMessage(AgentMessage message) {
                }

                @Override
                public void onClosed(Throwable failure) {
                    closed.complete(failure);
                }
            };
            JettyHTTPServer malformedServer = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    new AgentControlRoute(handler, AgentProtocolLimits.defaults(), Duration.ofSeconds(5)));
            try (TestAgentClient client = agentClient(malformedServer, material.serverCertificate())) {
                client.connect();
                client.send(new byte[]{(byte) 0xff});
                assertThat(closed.get(5, TimeUnit.SECONDS)).isNotNull();
            } finally {
                malformedServer.onStop();
            }
        }
    }

    @Test
    void timesOutQuietControlWithoutBlockingHttp1Route() throws Exception {
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
        AgentControlHandler handler = connection -> new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
            }

            @Override
            public void onClosed(Throwable failure) {
                closed.complete(failure);
            }
        };
        try (MaterialFixture material = material()) {
            AgentControlRoute control = new AgentControlRoute(
                    handler, AgentProtocolLimits.defaults(), Duration.ofMillis(200));
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    control,
                    new OkRoute());
            try (TestAgentClient client = agentClient(server, material.serverCertificate())) {
                client.connect();
                try (var ordinary = closeable(httpsConnection(server, clientContext(null, null)))) {
                    assertThat(ordinary.value().getResponseCode()).isEqualTo(200);
                    try (var response = ordinary.value().getInputStream()) {
                        assertThat(new String(response.readAllBytes(), StandardCharsets.UTF_8))
                                .isEqualTo("OK");
                    }
                }
                assertThat(closed.get(5, TimeUnit.SECONDS))
                        .hasMessageContaining("timed out");
            } finally {
                server.onStop();
            }
        }
    }

    @Test
    void rejectsOversizeAndInvalidTransportRequests() throws Exception {
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
        AgentControlHandler handler = connection -> new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
            }

            @Override
            public void onClosed(Throwable failure) {
                closed.complete(failure);
            }
        };
        try (MaterialFixture material = material()) {
            AgentControlRoute control = new AgentControlRoute(
                    handler, AgentProtocolLimits.defaults().withMaxMessageBytes(8), Duration.ofSeconds(5));
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    control);
            try (TestAgentClient client = agentClient(server, material.serverCertificate())) {
                client.connect();
                client.send(new byte[]{0x58, 0x20, 0, 0, 0, 0, 0, 0, 0});
                assertThat(closed.get(5, TimeUnit.SECONDS)).isNotNull();
                client.terminal.get(5, TimeUnit.SECONDS);

                HttpsURLConnection getConnection = (HttpsURLConnection) server.relativiseHttps(AgentControlRoute.PATH)
                        .openConnection();
                getConnection.setSSLSocketFactory(clientContext(null, null).getSocketFactory());
                getConnection.setHostnameVerifier((hostname, session) -> true);
                try (var get = closeable(getConnection)) {
                    assertThat(get.value().getResponseCode()).isEqualTo(405);
                }

                HttpsURLConnection postConnection = (HttpsURLConnection) server.relativiseHttps(AgentControlRoute.PATH)
                        .openConnection();
                postConnection.setSSLSocketFactory(clientContext(null, null).getSocketFactory());
                postConnection.setHostnameVerifier((hostname, session) -> true);
                postConnection.setRequestMethod("POST");
                postConnection.setDoOutput(true);
                try (var post = closeable(postConnection)) {
                    post.value().getOutputStream().close();
                    assertThat(post.value().getResponseCode()).isEqualTo(505);
                }
            } finally {
                server.onStop();
            }
        }
    }

    @Test
    void abortsBlockedOutputAndSettlesPendingSends() throws Exception {
        List<CompletableFuture<Void>> sends = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> sendsReady = new CompletableFuture<>();
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
        AgentControlHandler handler = connection -> new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
                for (int i = 0; i < 65; i++) {
                    sends.add(connection.send(new AgentMessage.RequestSessionList()).toCompletableFuture());
                }
                sendsReady.complete(null);
            }

            @Override
            public void onClosed(Throwable failure) {
                closed.complete(failure);
            }
        };
        try (MaterialFixture material = material()) {
            AgentControlRoute control = new AgentControlRoute(
                    handler, AgentProtocolLimits.defaults(), Duration.ofMillis(300));
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    control);
            try (TestAgentClient client = agentClient(server, material.serverCertificate(), false, 1)) {
                client.connect();
                client.send(AGENT_CODEC.encode(new AgentMessage.RequestSessionList()));
                sendsReady.get(5, TimeUnit.SECONDS);
                assertThat(sends).hasSize(65);
                assertThat(sends.getFirst()).isNotDone();
                assertThat(sends.getLast()).isCompletedExceptionally();
                assertThat(closed).isNotDone();

                assertThat(closed.get(5, TimeUnit.SECONDS)).hasMessageContaining("timed out");
                client.terminal.get(5, TimeUnit.SECONDS);
                awaitSettled(sends);
                assertThat(sends).allMatch(CompletableFuture::isDone);
            } finally {
                server.onStop();
            }
        }
    }

    @Test
    void cleansUpPeerResetAndServerShutdownAcrossSlowStreams() throws Exception {
        record StreamObservation(
                List<CompletableFuture<Void>> sends,
                CompletableFuture<Void> admitted,
                CompletableFuture<Void> closed) {
        }
        LinkedBlockingQueue<StreamObservation> opened = new LinkedBlockingQueue<>();
        AgentControlHandler handler = connection -> {
            List<CompletableFuture<Void>> sends = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 65; i++) {
                sends.add(connection.send(new AgentMessage.RequestSessionList()).toCompletableFuture());
            }
            CompletableFuture<Void> admitted = new CompletableFuture<>();
            CompletableFuture<Void> closed = new CompletableFuture<>();
            opened.add(new StreamObservation(sends, admitted, closed));
            return new AgentControlHandler.Session() {
                @Override
                public void onMessage(AgentMessage message) {
                    connection.handshakeComplete();
                    admitted.complete(null);
                }

                @Override
                public void onClosed(Throwable failure) {
                    closed.complete(null);
                }
            };
        };
        try (MaterialFixture material = material()) {
            AgentControlRoute control = new AgentControlRoute(
                    handler, AgentProtocolLimits.defaults(), Duration.ofSeconds(30));
            JettyHTTPServer server = startHttps(
                    material,
                    false,
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(),
                    control,
                    new OkRoute());
            List<TestAgentClient> clients = new ArrayList<>();
            List<StreamObservation> streams = new ArrayList<>();
            try {
                for (int i = 0; i < 6; i++) {
                    TestAgentClient client = agentClient(server, material.serverCertificate(), false, 1);
                    client.connect();
                    clients.add(client);
                    StreamObservation stream = opened.poll(5, TimeUnit.SECONDS);
                    assertThat(stream).isNotNull();
                    streams.add(stream);
                    client.send(AGENT_CODEC.encode(new AgentMessage.RequestSessionList()));
                    stream.admitted().get(5, TimeUnit.SECONDS);
                    assertThat(stream.sends()).hasSize(65);
                    assertThat(stream.sends().getFirst()).isNotDone();
                    assertThat(stream.sends().getLast()).isCompletedExceptionally();
                }
                try (var ordinary = closeable(httpsConnection(server, clientContext(null, null)))) {
                    assertThat(ordinary.value().getResponseCode()).isEqualTo(200);
                }

                clients.getFirst().reset();
                streams.getFirst().closed().get(5, TimeUnit.SECONDS);
                awaitSettled(streams.getFirst().sends());
                server.onStop();
                for (int i = 1; i < clients.size(); i++) {
                    streams.get(i).closed().get(5, TimeUnit.SECONDS);
                    awaitSettled(streams.get(i).sends());
                    clients.get(i).terminal.get(5, TimeUnit.SECONDS);
                }
            } finally {
                server.onStop();
                for (TestAgentClient client : clients) {
                    client.close();
                }
            }
        }
    }

    private static TestAgentClient agentClient(JettyHTTPServer server, X509Certificate certificate)
            throws Exception {
        return agentClient(server, certificate, true, 65_535);
    }

    private static TestAgentClient agentClient(
            JettyHTTPServer server, X509Certificate certificate, boolean demandData, int receiveWindow)
            throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, new char[0]);
        trust.setCertificateEntry("server", certificate);
        SslContextFactory.Client tls = new SslContextFactory.Client();
        tls.setTrustStore(trust);
        return new TestAgentClient(URI.create(server.relativiseHttps("").toString()), tls, demandData, receiveWindow);
    }

    private static byte[] concatenate(byte[]... items) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] item : items) {
            output.write(item);
        }
        return output.toByteArray();
    }

    private static void awaitMessages(List<AgentMessage> messages, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (messages.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(messages).hasSize(count);
    }

    private static void awaitSettled(List<CompletableFuture<Void>> futures) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (futures.stream().anyMatch(future -> !future.isDone()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(futures).allMatch(CompletableFuture::isDone);
    }

    private static void assertHttpsSucceeds(
            MaterialFixture material,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots,
            SSLContext clientContext) throws Exception {
        JettyHTTPServer server = startHttps(material, mode, clientRoots);
        try {
            try (var connection = closeable(httpsConnection(server, clientContext))) {
                assertThat(connection.value().getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
            }
        } finally {
            server.onStop();
        }
    }

    private static void assertHttpsFails(
            MaterialFixture material,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots,
            SSLContext clientContext) throws Exception {
        JettyHTTPServer server = startHttps(material, mode, clientRoots);
        try {
            try (var connection = closeable(httpsConnection(server, clientContext))) {
                assertThatThrownBy(connection.value()::getResponseCode)
                        .isInstanceOf(IOException.class);
            }
        } finally {
            server.onStop();
        }
    }

    private static JettyHTTPServer startHttps(
            MaterialFixture material,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots) throws IOException {
        return startHttps(material, false, mode, clientRoots, new OkRoute());
    }

    private static JettyHTTPServer startHttps(
            MaterialFixture material,
            boolean httpEnabled,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots,
            OrionHttpRoute... routes) throws IOException {
        return startHttps(
                material,
                httpEnabled,
                mode,
                clientRoots,
                NetworkUtils::findAvailablePort,
                routes);
    }

    private static JettyHTTPServer startHttps(
            MaterialFixture material,
            boolean httpEnabled,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots,
            HttpsPortSupplier portSupplier,
            OrionHttpRoute... routes) throws IOException {
        int attempts = 0;
        while (true) {
            JettyHTTPServer server = server(
                    httpConfiguration(httpEnabled),
                    desiredState(mode, clientRoots, portSupplier.next()),
                    material.owner().tls(),
                    routes);
            try {
                server.onStart();
                return server;
            } catch (IllegalStateException failure) {
                attempts++;
                if (!causedByBindException(failure) || attempts >= HTTPS_START_ATTEMPTS) {
                    throw failure;
                }
            }
        }
    }

    private static boolean causedByBindException(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof BindException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static HttpsURLConnection httpsConnection(JettyHTTPServer server, SSLContext context)
            throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) server.relativiseHttps("/ok").openConnection();
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        return connection;
    }

    private static <T extends HttpURLConnection> CloseableHttpConnection<T> closeable(T connection) {
        connection.setRequestProperty("Connection", "close");
        return new CloseableHttpConnection<>(connection);
    }

    private static SSLContext clientContext(KeyPair keyPair, X509Certificate certificate) throws Exception {
        javax.net.ssl.KeyManager[] keyManagers = null;
        if (keyPair != null) {
            char[] password = "client-password".toCharArray();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry("client", keyPair.getPrivate(), password, new Certificate[]{certificate});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            keyManagers = factory.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                keyManagers,
                new TrustManager[]{TrustAllX509TrustManager.INSTANCE},
                new java.security.SecureRandom());
        return context;
    }

    private static MaterialFixture material() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        store.write(SHARED_MATERIAL.snapshot(), null);
        OrionKeyMaterial owner = owner(store);
        return new MaterialFixture(
                owner,
                SHARED_MATERIAL.serverCertificate(),
                SHARED_MATERIAL.trustedClientKey(),
                SHARED_MATERIAL.trustedClientCertificate(),
                SHARED_MATERIAL.serverIssuerClientKey(),
                SHARED_MATERIAL.serverIssuerClientCertificate());
    }

    private static SharedMaterial sharedMaterial() {
        try {
            InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
            try (OrionKeyMaterial owner = owner(store)) {
                TestCertificateChain.Authority serverRoot = TestCertificateChain.root("Server Root");
                TestCertificateChain.Authority clientRoot = TestCertificateChain.root("Client Root");
                AcmeMaterialConfiguration clientProvisioning = new AcmeMaterialConfiguration(
                        ACCOUNT, IDENTITY, Optional.of(CLIENT_ROOT));
                AcmeKeyMaterial keys = owner.acme().acquire(clientProvisioning, 2048, 2048);
                owner.acme().installCertificateChain(
                        clientProvisioning,
                        List.of(TestCertificateChain.leaf("localhost", keys.domainKeyPair(), clientRoot)),
                        Optional.of(clientRoot.certificate()));
                AcmeMaterialConfiguration serverProvisioning = new AcmeMaterialConfiguration(
                        ACCOUNT, IDENTITY, Optional.of(SERVER_ROOT));
                X509Certificate serverCertificate = TestCertificateChain.leaf(
                        "localhost", keys.domainKeyPair(), serverRoot);
                owner.acme().installCertificateChain(
                        serverProvisioning,
                        List.of(serverCertificate),
                        Optional.of(serverRoot.certificate()));
                KeyPair trustedClientKey = TestCertificateChain.keyPair();
                KeyPair serverIssuerClientKey = TestCertificateChain.keyPair();
                return new SharedMaterial(
                        store.read().orElseThrow().bytes(),
                        serverCertificate,
                        trustedClientKey,
                        TestCertificateChain.leaf("trusted-client", trustedClientKey, clientRoot),
                        serverIssuerClientKey,
                        TestCertificateChain.leaf("server-issuer-client", serverIssuerClientKey, serverRoot));
            }
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static OrionKeyMaterial owner(InMemoryKeyMaterialContentStore store) throws Exception {
        return OrionKeyMaterial.open(
                store,
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                new SigningMaterialSet(SIGNING, List.of()),
                2048);
    }

    private static OrionDesiredState desiredState(
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots) throws IOException {
        return desiredState(mode, clientRoots, NetworkUtils.findAvailablePort());
    }

    private static OrionDesiredState desiredState(
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots,
            int port) {
        List<OrionMaterialReference> references = new java.util.ArrayList<>();
        for (TrustedCertificateDescriptor root : clientRoots) {
            references.add(new OrionMaterialReference(root.alias().value(), root.version().value()));
        }
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                true,
                "localhost",
                port,
                null,
                Optional.of(reference(IDENTITY)),
                Optional.of(reference(SERVER_ROOT)),
                mode,
                references,
                Optional.empty());
        return desiredState(Optional.of(https));
    }

    @FunctionalInterface
    private interface HttpsPortSupplier {
        int next() throws IOException;
    }

    private static OrionDesiredState desiredStateWithoutHttps() {
        return desiredState(Optional.empty());
    }

    private static OrionDesiredState desiredState(Optional<OrionHttpsConfiguration> https) {
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), https),
                List.of()), Optional.of("test-revision"));
        return desiredState;
    }

    private static OrionConfiguration httpConfiguration(boolean enabled) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().getKeyMaterial().setClusterId(CLUSTER);
        configuration.getTransport().setHttp(new HttpTransportConfig("127.0.0.1", 0));
        configuration.getTransport().getHttp().setEnabled(enabled);
        return configuration;
    }

    private static JettyHTTPServer server(
            OrionConfiguration bootstrap,
            OrionDesiredState desiredState,
            TlsCapability tls,
            OrionHttpRoute... routes) {
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(routes)),
                new OrionHttpResponseWriter(new ObjectMapper()));
        return new JettyHTTPServer(bootstrap, desiredState, tls, servlet, null);
    }

    private static KeyMaterialDescriptor descriptor(String alias, KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias),
                purpose,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1),
                KeyMaterialScope.cluster(CLUSTER));
    }

    private static TrustedCertificateDescriptor trusted(String alias) {
        return new TrustedCertificateDescriptor(
                new KeyMaterialAlias(alias),
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1),
                KeyMaterialScope.cluster(CLUSTER));
    }

    private static OrionMaterialReference reference(KeyMaterialDescriptor descriptor) {
        return new OrionMaterialReference(descriptor.alias().value(), descriptor.version().value());
    }

    private static OrionMaterialReference reference(TrustedCertificateDescriptor descriptor) {
        return new OrionMaterialReference(descriptor.alias().value(), descriptor.version().value());
    }

    private static void request(URL url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setReadTimeout(10_000);
            connection.getResponseCode();
        } catch (IOException ignored) {
        }
    }

    private static final class OkRoute extends AbstractOrionHttpRoute {
        private OkRoute() {
            super("/ok", OrionHttpRouteDefinition.Method.GET);
        }

        @Override
        protected OrionHttpResponse doGet(jakarta.servlet.http.HttpServletRequest req) {
            return OrionHttpResponse.text(HttpURLConnection.HTTP_OK, "OK");
        }
    }

    private record CloseableHttpConnection<T extends HttpURLConnection>(T value) implements AutoCloseable {
        @Override
        public void close() {
            value.disconnect();
        }
    }

    private static final class JavascriptRoute extends AbstractOrionHttpRoute {
        private JavascriptRoute() {
            super("/app.js", OrionHttpRouteDefinition.Method.GET);
        }

        @Override
        protected OrionHttpResponse doGet(jakarta.servlet.http.HttpServletRequest req) {
            String source = "export const value = 'orion';\n".repeat(100);
            return OrionHttpResponse.resource(
                    HttpURLConnection.HTTP_OK,
                    source.getBytes(StandardCharsets.UTF_8),
                    "application/javascript; charset=utf-8");
        }
    }

    private static final class BlockingRoute extends AbstractOrionHttpRoute {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingRoute() {
            super("/block", OrionHttpRouteDefinition.Method.GET);
        }

        @Override
        protected OrionHttpResponse doGet(jakarta.servlet.http.HttpServletRequest req) {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OrionHttpResponse.text(HttpURLConnection.HTTP_OK, "OK");
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class TestAgentClient implements AutoCloseable {
        private final URI endpoint;
        private final SslContextFactory.Client tls;
        private final HTTP2Client client = new HTTP2Client();
        private final AgentProtocolDecoder decoder = new AgentProtocolDecoder(AgentProtocolLimits.defaults());
        private final LinkedBlockingQueue<AgentMessage> replies = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> accepted = new CompletableFuture<>();
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();
        private final boolean demandData;
        private Stream stream;

        private TestAgentClient(
                URI endpoint, SslContextFactory.Client tls, boolean demandData, int receiveWindow) {
            this.endpoint = endpoint;
            this.tls = tls;
            this.demandData = demandData;
            tls.setEndpointIdentificationAlgorithm("HTTPS");
            client.setProtocols(List.of("h2"));
            client.setUseALPN(true);
            client.setInitialStreamRecvWindow(receiveWindow);
        }

        private void connect() throws Exception {
            tls.start();
            client.start();
            int port = endpoint.getPort() < 0 ? 443 : endpoint.getPort();
            Session session = client.connect(
                    tls,
                    new InetSocketAddress(endpoint.getHost(), port),
                    new Session.Listener() {
                        @Override
                        public void onFailure(Session session, Throwable failure, Callback callback) {
                            callback.succeeded();
                            terminal.completeExceptionally(failure);
                        }
                    }).get(5, TimeUnit.SECONDS);
            MetaData.Request request = new MetaData.Request(
                    "POST",
                    HttpURI.from(endpoint.resolve(AgentControlRoute.PATH)),
                    HttpVersion.HTTP_2,
                    HttpFields.EMPTY);
            stream = session.newStream(new HeadersFrame(request, null, false), new Stream.Listener() {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame) {
                    if (frame.getMetaData() instanceof MetaData.Response response
                            && response.getStatus() == 200 && !frame.isEndStream()) {
                        accepted.complete(null);
                        if (demandData) {
                            stream.demand();
                        }
                    } else {
                        accepted.completeExceptionally(new AssertionError("control response was not non-final 200"));
                    }
                }

                @Override
                public void onDataAvailable(Stream stream) {
                    Stream.Data data;
                    while ((data = stream.readData()) != null) {
                        try {
                            SequenceDecodeResult<AgentMessage> result = decoder.accept(data.frame().getByteBuffer());
                            for (SequenceDecodeResult.Outcome<AgentMessage> outcome : result.outcomes()) {
                                if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded) {
                                    replies.add(decoded.value());
                                }
                            }
                            result.terminalIssue().ifPresent(issue -> terminal.completeExceptionally(issue.exception()));
                            if (data.frame().isEndStream()) {
                                terminal.complete(null);
                            }
                        } finally {
                            data.release();
                        }
                    }
                    if (demandData && !terminal.isDone()) {
                        stream.demand();
                    }
                }

                @Override
                public void onReset(Stream stream, ResetFrame frame, Callback callback) {
                    callback.succeeded();
                    terminal.complete(null);
                }

                @Override
                public void onFailure(Stream stream, int error, String reason, Throwable failure,
                                      Callback callback) {
                    callback.succeeded();
                    terminal.completeExceptionally(failure == null
                            ? new IOException("stream failure " + error + ": " + reason) : failure);
                }

                @Override
                public void onClosed(Stream stream) {
                    terminal.complete(null);
                }
            }).get(5, TimeUnit.SECONDS);
            accepted.get(5, TimeUnit.SECONDS);
        }

        private void send(byte[] bytes) throws Exception {
            CompletableFuture<Void> sent = new CompletableFuture<>();
            stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(bytes), false),
                    Callback.from(() -> sent.complete(null), sent::completeExceptionally));
            sent.get(5, TimeUnit.SECONDS);
        }

        private void reset() throws Exception {
            CompletableFuture<Void> reset = new CompletableFuture<>();
            stream.reset(new ResetFrame(stream.getId(), 0),
                    Callback.from(() -> reset.complete(null), reset::completeExceptionally));
            reset.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            if (stream != null && !stream.isClosed()) {
                stream.reset(new ResetFrame(stream.getId(), 0), Callback.NOOP);
            }
            client.stop();
            tls.stop();
        }
    }

    private record MaterialFixture(
            OrionKeyMaterial owner,
            X509Certificate serverCertificate,
            KeyPair trustedClientKey,
            X509Certificate trustedClientCertificate,
            KeyPair serverIssuerClientKey,
            X509Certificate serverIssuerClientCertificate) implements AutoCloseable {
        @Override
        public void close() {
            owner.close();
        }
    }

    private record SharedMaterial(
            byte[] snapshot,
            X509Certificate serverCertificate,
            KeyPair trustedClientKey,
            X509Certificate trustedClientCertificate,
            KeyPair serverIssuerClientKey,
            X509Certificate serverIssuerClientCertificate) {
    }
}
