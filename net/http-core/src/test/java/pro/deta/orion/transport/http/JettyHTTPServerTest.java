package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JettyHTTPServerTest {
    private static final String CLUSTER = "test-cluster";
    private static final KeyMaterialDescriptor SIGNING = descriptor(
            "server-signing-v1", KeyMaterialPurpose.SERVER_SIGNING);
    private static final KeyMaterialDescriptor ACCOUNT = descriptor(
            "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT);
    private static final KeyMaterialDescriptor IDENTITY = descriptor(
            "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY);
    private static final TrustedCertificateDescriptor SERVER_ROOT = trusted("server-root-v1");
    private static final TrustedCertificateDescriptor CLIENT_ROOT = trusted("client-root-v1");

    @Test
    void servesMaterialBackedHttpsWithoutRootInTheServerChain() throws Exception {
        try (MaterialFixture material = material()) {
            OrionConfiguration bootstrap = httpConfiguration(true);
            OrionDesiredState desiredState = desiredState(
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of());
            JettyHTTPServer server = server(bootstrap, desiredState, material.owner().tls(), new OkRoute());
            server.onStart();

            try {
                HttpURLConnection http = (HttpURLConnection) server.relativiseHttp("/ok").openConnection();
                assertThat(http.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

                HttpsURLConnection https = httpsConnection(server, clientContext(null, null));
                assertThat(https.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
                assertThat(https.getServerCertificates())
                        .extracting(Certificate::getPublicKey)
                        .containsExactly(material.serverCertificate().getPublicKey());
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
        try (OrionKeyMaterial owner = owner()) {
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
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Accept-Encoding", "gzip");

            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(connection.getHeaderField("Content-Encoding")).isEqualTo("gzip");
            assertThat(connection.getInputStream().readAllBytes()).startsWith((byte) 0x1f, (byte) 0x8b);
        } finally {
            server.onStop();
        }
    }

    private static void assertHttpsSucceeds(
            MaterialFixture material,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots,
            SSLContext clientContext) throws Exception {
        JettyHTTPServer server = startHttps(material, mode, clientRoots);
        try {
            assertThat(httpsConnection(server, clientContext).getResponseCode())
                    .isEqualTo(HttpURLConnection.HTTP_OK);
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
            assertThatThrownBy(() -> httpsConnection(server, clientContext).getResponseCode())
                    .isInstanceOf(IOException.class);
        } finally {
            server.onStop();
        }
    }

    private static JettyHTTPServer startHttps(
            MaterialFixture material,
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots) throws IOException {
        JettyHTTPServer server = server(
                httpConfiguration(false),
                desiredState(mode, clientRoots),
                material.owner().tls(),
                new OkRoute());
        server.onStart();
        return server;
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
        OrionKeyMaterial owner = owner();
        try {
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
            return new MaterialFixture(
                    owner,
                    serverCertificate,
                    trustedClientKey,
                    TestCertificateChain.leaf("trusted-client", trustedClientKey, clientRoot),
                    serverIssuerClientKey,
                    TestCertificateChain.leaf("server-issuer-client", serverIssuerClientKey, serverRoot));
        } catch (Exception failure) {
            owner.close();
            throw failure;
        }
    }

    private static OrionKeyMaterial owner() throws Exception {
        return OrionKeyMaterial.open(
                new InMemoryKeyMaterialContentStore(),
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                new SigningMaterialSet(SIGNING, List.of()),
                2048);
    }

    private static OrionDesiredState desiredState(
            OrionHttpsConfiguration.ClientAuthentication mode,
            List<TrustedCertificateDescriptor> clientRoots) throws IOException {
        List<OrionMaterialReference> references = new java.util.ArrayList<>();
        for (TrustedCertificateDescriptor root : clientRoots) {
            references.add(new OrionMaterialReference(root.alias().value(), root.version().value()));
        }
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                true,
                "localhost",
                NetworkUtils.findAvailablePort(),
                null,
                Optional.of(reference(IDENTITY)),
                Optional.of(reference(SERVER_ROOT)),
                mode,
                references,
                Optional.empty());
        return desiredState(Optional.of(https));
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
            OrionHttpRoute route) {
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(route)),
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
            super("/ok", "GET");
        }

        @Override
        protected OrionHttpResponse doGet(jakarta.servlet.http.HttpServletRequest req) {
            return OrionHttpResponse.text(HttpURLConnection.HTTP_OK, "OK");
        }
    }

    private static final class JavascriptRoute extends AbstractOrionHttpRoute {
        private JavascriptRoute() {
            super("/app.js", "GET");
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
            super("/block", "GET");
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
}
