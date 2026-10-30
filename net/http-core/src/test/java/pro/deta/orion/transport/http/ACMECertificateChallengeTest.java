package pro.deta.orion.transport.http;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import pro.deta.orion.event.OrionEventManager;

import pro.deta.orion.acme.AcmeCertificateService;
import pro.deta.orion.acme.AcmeCertificateIssuer;
import pro.deta.orion.acme.AcmeCertificateIssueRequest;
import pro.deta.orion.acme.IssuedAcmeCertificate;
import pro.deta.orion.acme.AcmeHttpChallengeService;
import pro.deta.orion.test.certificates.TestCertificateChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.HttpTransportConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.test.integration.TestConfiguration;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime events =
            new pro.deta.orion.test.util.OrionTestRuntime();

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
        try (OrionKeyMaterial material = keyMaterial()) {
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredStateWithAcme().editor(),
                    material,
                    issuer, null, events.executor());
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
    }

    private AcmeHttpTestServer startHttp() throws IOException {
        return startHttp(new AcmeHttpChallengeService());
    }

    private AcmeHttpTestServer startHttp(
            AcmeHttpChallengeService challengeService,
            OrionHttpRoute... additionalRoutes) throws IOException {
        BootstrapConfiguration orionConfiguration = new BootstrapConfiguration();
        BootstrapConfiguration.AppTransport transports = new BootstrapConfiguration.AppTransport();
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
                desiredStateWithoutHttps().editor(),
                OrionKeyMaterial.unavailable(),
                rootServlet,
                null, null);
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

    private TestConfiguration desiredStateWithoutHttps() {
        TestConfiguration desiredState = new TestConfiguration(events);
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(), List.of(), List.of(), List.of(),
                List.of(), List.of()),
                List.of()), Optional.of("test-revision"));
        return desiredState;
    }

    private TestConfiguration desiredStateWithAcme() {
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true,
                URI.create("acme://example-ca"),
                "admin@example.test",
                List.of("example.test"),
                "ORION",
                Optional.of(new KeyMaterialId("acme-account-v1", 1)),
                30,
                40,
                true,
                false, Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                false,
                "localhost",
                8443,
                URI.create("https://example.test"),
                Optional.of(new KeyMaterialId("https-identity-v1", 1)),
                Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(),
                Optional.of(acme));
        TestConfiguration desiredState = new TestConfiguration(events);
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.of(https), List.of(), List.of(), List.of(),
                List.of(), List.of()),
                List.of()), Optional.of("test-revision"));
        return desiredState;
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
        protected OrionHttpResponse doPost(jakarta.servlet.http.HttpServletRequest request) throws IOException {
            request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE, AcmeAdministrationTest.admin());
            return super.doPost(request);
        }

        @Override
        public OrionHttpRouteDefinition definition() {
            return new OrionHttpRouteDefinition(
                    OrionAdminPaths.ACME_CERTIFICATE,
                    OrionHttpRouteDefinition.Authorization.ANONYMOUS,
                    OrionHttpRouteDefinition.Method.GET,
                    OrionHttpRouteDefinition.Method.POST);
        }
    }

    private static OrionKeyMaterial keyMaterial() throws Exception {
        return OrionKeyMaterial.open(new KeyMaterialRepository(
                new InMemoryBlobStorage(),
                new KeyMaterialCodec(KeyMaterialOptions.pkcs12("test-password".toCharArray())),
                new OrionEventManager()));
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
