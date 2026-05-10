package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.Status;

import java.io.IOException;
import java.io.Writer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeCertificateIssuerTest {
    private static final String TOKEN = "test-token";
    private static final String AUTHORIZATION = "test-authorization";

    @Test
    void issuesCertificateThroughRegisteredHttp01Challenge() throws Exception {
        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        RecordingAcmeClient acmeClient = new RecordingAcmeClient(challengeService);
        AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(challengeService, acmeClient);

        IssuedAcmeCertificate certificate = issuer.issue(request());

        assertThat(certificate.domains()).containsExactly("example.test");
        assertThat(certificate.nginxPem())
                .contains("-----BEGIN CERTIFICATE-----")
                .contains("-----BEGIN RSA PRIVATE KEY-----");
        assertThat(acmeClient.order.executed).isTrue();
        assertThat(acmeClient.order.organization).isEqualTo("ORION");
        assertThat(acmeClient.authorization.challenge.triggered).isTrue();
        assertThat(challengeService.authorizationFor(TOKEN)).isNull();
    }

    @Test
    void removesHttp01ChallengeWhenAuthorizationFails() {
        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        RecordingAcmeClient acmeClient = new RecordingAcmeClient(challengeService);
        acmeClient.authorization.completionStatus = Status.INVALID;
        AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(challengeService, acmeClient);

        assertThatThrownBy(() -> issuer.issue(request()))
                .isInstanceOf(AcmeCertificateIssueException.class)
                .hasMessageContaining("finished with INVALID");

        assertThat(acmeClient.authorization.challenge.triggered).isTrue();
        assertThat(challengeService.authorizationFor(TOKEN)).isNull();
    }

    @Test
    void rejectsPendingAuthorizationWithoutHttp01Challenge() {
        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        RecordingAcmeClient acmeClient = new RecordingAcmeClient(challengeService);
        acmeClient.authorization.challenge = null;
        AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(challengeService, acmeClient);

        assertThatThrownBy(() -> issuer.issue(request()))
                .isInstanceOf(AcmeCertificateIssueException.class)
                .hasMessageContaining("has no http-01 challenge");
    }

    private static AcmeCertificateIssueRequest request() throws Exception {
        return new AcmeCertificateIssueRequest(
                "acme://example-ca",
                "admin@example.test",
                keyPair(),
                keyPair(),
                List.of("example.test"),
                "ORION",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                true);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private static final class RecordingAcmeClient implements AcmeCertificateIssuer.AcmeClient {
        private final RecordingAcmeAuthorization authorization;
        private final RecordingAcmeOrder order;

        private RecordingAcmeClient(AcmeHttpChallengeService challengeService) {
            authorization = new RecordingAcmeAuthorization(challengeService);
            order = new RecordingAcmeOrder(authorization);
        }

        @Override
        public AcmeCertificateIssuer.AcmeAccount createAccount(AcmeCertificateIssueRequest request) {
            return domains -> order;
        }
    }

    private static final class RecordingAcmeOrder implements AcmeCertificateIssuer.AcmeOrder {
        private final RecordingAcmeAuthorization authorization;
        private boolean executed;
        private String organization;

        private RecordingAcmeOrder(RecordingAcmeAuthorization authorization) {
            this.authorization = authorization;
        }

        @Override
        public List<AcmeCertificateIssuer.AcmeAuthorization> authorizations() {
            return List.of(authorization);
        }

        @Override
        public Status waitUntilReady(Duration timeout) {
            return Status.READY;
        }

        @Override
        public void execute(KeyPair domainKeyPair, String organization) {
            this.organization = organization;
            executed = true;
        }

        @Override
        public Status waitForCompletion(Duration timeout) {
            return Status.VALID;
        }

        @Override
        public void writeCertificate(Writer writer) throws IOException {
            writer.write("""
                    -----BEGIN CERTIFICATE-----
                    TEST
                    -----END CERTIFICATE-----
                    """);
        }
    }

    private static final class RecordingAcmeAuthorization implements AcmeCertificateIssuer.AcmeAuthorization {
        private Status completionStatus = Status.VALID;
        private RecordingAcmeHttp01Challenge challenge;

        private RecordingAcmeAuthorization(AcmeHttpChallengeService challengeService) {
            challenge = new RecordingAcmeHttp01Challenge(challengeService);
        }

        @Override
        public String identifier() {
            return "example.test";
        }

        @Override
        public Status status() {
            return Status.PENDING;
        }

        @Override
        public AcmeCertificateIssuer.AcmeHttp01Challenge http01Challenge() {
            return challenge;
        }

        @Override
        public Status waitForCompletion(Duration timeout) {
            return completionStatus;
        }
    }

    private static final class RecordingAcmeHttp01Challenge implements AcmeCertificateIssuer.AcmeHttp01Challenge {
        private final AcmeHttpChallengeService challengeService;
        private boolean triggered;

        private RecordingAcmeHttp01Challenge(AcmeHttpChallengeService challengeService) {
            this.challengeService = challengeService;
        }

        @Override
        public String token() {
            return TOKEN;
        }

        @Override
        public String authorization() {
            return AUTHORIZATION;
        }

        @Override
        public void trigger() {
            assertThat(challengeService.authorizationFor(TOKEN)).isEqualTo(AUTHORIZATION);
            triggered = true;
        }
    }
}
