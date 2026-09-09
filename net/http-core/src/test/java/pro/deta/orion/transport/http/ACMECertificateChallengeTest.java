package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.AcmeKeyMaterial;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.AcmeMaterialConfiguration;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class ACMECertificateChallengeTest {
    @Test
    public void testChallengeHttp01() throws Exception {
        try (AcmeHttpTestServer server = startHttp()) {
            server.challengeService().registerChallenge("test-token", "test-authorization");

            HttpURLConnection connection = get(server.challengeUrl("test-token"));
            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(new String(connection.getInputStream().readAllBytes())).isEqualTo("test-authorization");
        }
    }

    @Test
    void servesHttp01CallbackWhileAdmittedIssuanceWaitsForAuthorization() throws Exception {
        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        WaitingChallengeIssuer issuer = new WaitingChallengeIssuer(challengeService);
        AcmeCertificateService service = new AcmeCertificateService(
                bootstrap(),
                desiredStateWithAcme(),
                new TestAcmeKeyMaterial(),
                issuer);
        try (AcmeHttpTestServer server = startHttp(
                challengeService,
                new TestAdminAcmeCertificateRoute(service));
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<HttpResponse> issuance = executor.submit(() -> post(server.adminAcmeUrl()));

            assertThat(issuer.awaitAuthorization()).isTrue();
            assertThat(issuance.isDone()).isFalse();
            try {
                HttpURLConnection callback = get(server.challengeUrl("active-token"));
                assertThat(callback.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
                assertThat(new String(callback.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("active-authorization");
            } finally {
                issuer.completeAuthorization();
            }

            HttpResponse response = issuance.get(5, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(response.body()).contains("-----BEGIN CERTIFICATE-----");
            assertThat(get(server.challengeUrl("active-token")).getResponseCode())
                    .isEqualTo(HttpURLConnection.HTTP_NOT_FOUND);
        }
    }

    private static AcmeHttpTestServer startHttp() throws IOException {
        return startHttp(new AcmeHttpChallengeService());
    }

    private static AcmeHttpTestServer startHttp(
            AcmeHttpChallengeService challengeService,
            OrionHttpRoute... additionalRoutes) throws IOException {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", 0));
        orionConfiguration.setTransport(transports);

        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new AcmeHttpChallengeRoute(challengeService));
        routes.addAll(List.of(additionalRoutes));
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(routes),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(
                orionConfiguration,
                desiredStateWithoutHttps(),
                TlsCapability.unavailable(),
                rootServlet,
                null);
        server.onStart();
        return new AcmeHttpTestServer(server, challengeService);
    }

    private record AcmeHttpTestServer(
            JettyHTTPServer server,
            AcmeHttpChallengeService challengeService) implements AutoCloseable {
        private URL challengeUrl(String token) throws IOException {
            return server.relativiseHttp(AcmeHttpChallengeRoute.CHALLENGE_PREFIX + token);
        }

        private URL adminAcmeUrl() throws IOException {
            return server.relativiseHttp(OrionAdminPaths.ACME_CERTIFICATE);
        }

        @Override
        public void close() {
            server.onStop();
        }
    }

    @Test
    public void startsHttpChallengeWithoutHttpsMaterial() throws Exception {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", 0));
        orionConfiguration.setTransport(transports);

        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new OkRoute());
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(routes),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(
                orionConfiguration,
                desiredStateWithoutHttps(),
                TlsCapability.unavailable(),
                rootServlet,
                null);
        server.onStart();

        try {
            HttpURLConnection connection = get(server.relativiseHttp("/ok"));
            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
        } finally {
            server.onStop();
        }
    }

    private static OrionDesiredState desiredStateWithoutHttps() {
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty()),
                List.of()), Optional.of("test-revision"));
        return desiredState;
    }

    private static OrionConfiguration bootstrap() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().getKeyMaterial().setClusterId("test-cluster");
        return configuration;
    }

    private static OrionDesiredState desiredStateWithAcme() {
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true,
                URI.create("acme://example-ca"),
                "admin@example.test",
                List.of("example.test"),
                "ORION",
                Optional.of(new OrionMaterialReference("acme-account-v1", 1)),
                30,
                40,
                true,
                false);
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                false,
                "localhost",
                8443,
                URI.create("https://example.test"),
                Optional.of(new OrionMaterialReference("https-identity-v1", 1)),
                Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(),
                Optional.of(acme));
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.of(https)),
                List.of()), Optional.of("test-revision"));
        return desiredState;
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

    private static HttpURLConnection get(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        return connection;
    }

    private static HttpResponse post(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        connection.setFixedLengthStreamingMode(0);
        connection.setDoOutput(true);
        int status = connection.getResponseCode();
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new HttpResponse(status, body);
    }

    private record HttpResponse(int status, String body) {
    }

    private static final class TestAdminAcmeCertificateRoute extends OrionAdminAcmeCertificateRoute {
        private TestAdminAcmeCertificateRoute(AcmeCertificateService certificateService) {
            super(certificateService, new ObjectMapper());
        }

        @Override
        protected void authorize(jakarta.servlet.http.HttpServletRequest req) {
        }
    }

    private static final class TestAcmeKeyMaterial implements AcmeKeyMaterialCapability {
        private final KeyPair accountKeyPair;
        private final KeyPair domainKeyPair;

        private TestAcmeKeyMaterial() throws Exception {
            accountKeyPair = TestCertificateChain.keyPair();
            domainKeyPair = TestCertificateChain.keyPair();
        }

        @Override
        public AcmeKeyMaterial acquire(
                AcmeMaterialConfiguration configuration,
                int accountKeySize,
                int domainKeySize) {
            return new AcmeKeyMaterial(accountKeyPair, domainKeyPair);
        }

        @Override
        public void installCertificateChain(
                AcmeMaterialConfiguration configuration,
                List<? extends Certificate> certificateChain,
                Optional<X509Certificate> issuerTrustAnchor) {
        }

        @Override
        public Optional<List<X509Certificate>> certificateChain(AcmeMaterialConfiguration configuration) {
            return Optional.empty();
        }

        @Override
        public Optional<X509Certificate> issuerTrustAnchor(AcmeMaterialConfiguration configuration) {
            return Optional.empty();
        }
    }

    private static final class WaitingChallengeIssuer extends AcmeCertificateIssuer {
        private final AcmeHttpChallengeService challengeService;
        private final CountDownLatch authorizationWaiting = new CountDownLatch(1);
        private final CountDownLatch authorizationComplete = new CountDownLatch(1);

        private WaitingChallengeIssuer(AcmeHttpChallengeService challengeService) {
            super(challengeService);
            this.challengeService = challengeService;
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
            challengeService.registerChallenge("active-token", "active-authorization");
            authorizationWaiting.countDown();
            try {
                if (!authorizationComplete.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to complete authorization");
                }
                X509Certificate certificate = TestCertificateChain.selfSignedLeaf(
                        "example.test", request.domainKeyPair());
                return new IssuedAcmeCertificate(request.domains(), List.of(certificate));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                challengeService.removeChallenge("active-token");
            }
        }

        private boolean awaitAuthorization() throws InterruptedException {
            return authorizationWaiting.await(5, TimeUnit.SECONDS);
        }

        private void completeAuthorization() {
            authorizationComplete.countDown();
        }
    }

}
