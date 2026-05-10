package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.AcmeConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeCertificateServiceTest {
    private static final String CERTIFICATE_PEM = """
            -----BEGIN CERTIFICATE-----
            TEST
            -----END CERTIFICATE-----
            """;
    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN RSA PRIVATE KEY-----
            TEST
            -----END RSA PRIVATE KEY-----
            """;

    @TempDir
    private Path tempDir;

    @Test
    void issuesCertificateUsingHttpsAcmeConfiguration() throws Exception {
        OrionConfiguration configuration = configuration();
        RecordingIssuer issuer = new RecordingIssuer();
        AcmeCertificateService service = new AcmeCertificateService(configuration, issuer);

        IssuedAcmeCertificate certificate = service.issue(new AcmeCertificateService.IssueRequest(
                null, null, null, null, null, null, null, true));

        assertThat(issuer.lastRequest.directoryUrl()).isEqualTo("acme://letsencrypt.org/staging");
        assertThat(issuer.lastRequest.accountEmail()).isEqualTo("admin@example.test");
        assertThat(issuer.lastRequest.domains()).containsExactly("example.test");
        assertThat(issuer.lastRequest.organization()).isEqualTo("ORION");
        assertThat(issuer.lastRequest.authorizationTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(issuer.lastRequest.orderTimeout()).isEqualTo(Duration.ofSeconds(40));
        assertThat(issuer.lastRequest.agreeToTermsOfService()).isTrue();
        assertThat(certificate.nginxPem()).contains(CERTIFICATE_PEM).contains(PRIVATE_KEY_PEM);
        assertThat(Files.readString(tempDir.resolve("certs/nginx.pem"))).isEqualTo(certificate.nginxPem());
    }

    @Test
    void rejectsRequestedDomainsUnlessConfigurationAllowsIt() {
        OrionConfiguration configuration = configuration();
        AcmeCertificateService service = new AcmeCertificateService(configuration, new RecordingIssuer());

        assertThatThrownBy(() -> service.issue(new AcmeCertificateService.IssueRequest(
                null, null, List.of("other.example.test"), null, null, null, null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Requested ACME domains are not allowed");
    }

    @Test
    void allowsRequestedDomainsWhenConfigurationAllowsIt() throws Exception {
        OrionConfiguration configuration = configuration();
        configuration.getTransport().getHttps().getAcme().setAllowRequestedDomains(true);
        RecordingIssuer issuer = new RecordingIssuer();
        AcmeCertificateService service = new AcmeCertificateService(configuration, issuer);

        service.issue(new AcmeCertificateService.IssueRequest(
                null, null, List.of("other.example.test"), null, null, null, null, false));

        assertThat(issuer.lastRequest.domains()).containsExactly("other.example.test");
    }

    @Test
    void downloadsSavedNginxCertificate() throws Exception {
        OrionConfiguration configuration = configuration();
        Path certificatePath = tempDir.resolve("certs/nginx.pem");
        Files.createDirectories(certificatePath.getParent());
        Files.writeString(certificatePath, CERTIFICATE_PEM + PRIVATE_KEY_PEM);
        AcmeCertificateService service = new AcmeCertificateService(configuration, new RecordingIssuer());

        Optional<String> certificate = service.savedNginxCertificate();

        assertThat(certificate).contains(CERTIFICATE_PEM + PRIVATE_KEY_PEM);
    }

    private OrionConfiguration configuration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        HttpsTransportConfig https = configuration.getTransport().getHttps();
        AcmeConfig acme = https.getAcme();
        acme.setDirectoryUrl("acme://letsencrypt.org/staging");
        acme.setAccountEmail("admin@example.test");
        acme.setDomains(List.of("example.test"));
        acme.setOrganization("ORION");
        acme.setAccountKeyPath("keys/account.keypair");
        acme.setDomainKeyPath("keys/domain.keypair");
        acme.setCertificatePath("certs/nginx.pem");
        acme.setAuthorizationTimeoutSeconds(30);
        acme.setOrderTimeoutSeconds(40);
        acme.setAgreeToTermsOfService(true);
        return configuration;
    }

    private static final class RecordingIssuer extends AcmeCertificateIssuer {
        private AcmeCertificateIssueRequest lastRequest;

        private RecordingIssuer() {
            super(new AcmeHttpChallengeService());
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
            lastRequest = request;
            return new IssuedAcmeCertificate(request.domains(), CERTIFICATE_PEM, PRIVATE_KEY_PEM);
        }
    }
}
