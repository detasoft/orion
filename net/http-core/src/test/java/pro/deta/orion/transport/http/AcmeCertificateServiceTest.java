package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.OrionDesiredState;
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
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeCertificateServiceTest {
    private static final String CLUSTER = "test-cluster";
    private static final KeyMaterialDescriptor SIGNING = descriptor(
            "server-signing-v1", KeyMaterialPurpose.SERVER_SIGNING);

    @TempDir
    private Path tempDir;

    @Test
    void issuesFromDesiredStatePersistsInMaterialStoreAndReloadsWithoutLegacyFiles() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        OrionDesiredState desiredState = desiredState(false);
        OrionConfiguration bootstrap = bootstrap();

        try (OrionKeyMaterial owner = owner(store)) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap, desiredState, owner.acme(), issuer);

            IssuedAcmeCertificate certificate = service.issue(AcmeCertificateService.IssueRequest.EMPTY);

            assertThat(issuer.lastRequest.directoryUrl()).isEqualTo("acme://letsencrypt.org/staging");
            assertThat(issuer.lastRequest.accountEmail()).isEqualTo("admin@example.test");
            assertThat(issuer.lastRequest.domains()).containsExactly("example.test");
            assertThat(issuer.lastRequest.organization()).isEqualTo("ORION");
            assertThat(issuer.lastRequest.authorizationTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(issuer.lastRequest.orderTimeout()).isEqualTo(Duration.ofSeconds(40));
            assertThat(issuer.lastRequest.agreeToTermsOfService()).isTrue();
            assertThat(certificate.certificateChain()).hasSize(1);
            assertThat(service.savedCertificate()).isPresent();
        }

        try (OrionKeyMaterial owner = owner(store)) {
            AcmeCertificateService restarted = new AcmeCertificateService(
                    bootstrap, desiredState, owner.acme(), new RecordingIssuer(false));

            assertThat(restarted.savedCertificate().orElseThrow().certificateChain()).hasSize(1);
        }

        assertThat(Files.exists(tempDir.resolve("acme/account.keypair"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("acme/domain.keypair"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("acme/nginx.pem"))).isFalse();
    }

    @Test
    void rejectsRequestedDomainsUnlessDesiredStateAllowsIt() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), new RecordingIssuer(false));

            assertThatThrownBy(() -> service.issue(new AcmeCertificateService.IssueRequest(
                    null, null, List.of("other.example.test"), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Requested ACME domains are not allowed");
        }
    }

    @Test
    void allowsRequestedDomainsWhenDesiredStateAllowsIt() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(true), owner.acme(), issuer);

            service.issue(new AcmeCertificateService.IssueRequest(
                    null, null, List.of("other.example.test"), null, null, null, null));

            assertThat(issuer.lastRequest.domains()).containsExactly("other.example.test");
        }
    }

    @Test
    void invalidIssuedChainDoesNotBecomeSavedMaterial() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial owner = owner(store)) {
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), new RecordingIssuer(true));

            assertThatThrownBy(() -> service.issue(AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessageContaining("store issued ACME certificate");
            assertThat(service.savedCertificate()).isEmpty();
        }
    }

    private OrionConfiguration bootstrap() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getBootstrap().getKeyMaterial().setClusterId(CLUSTER);
        return configuration;
    }

    private static OrionDesiredState desiredState(boolean allowRequestedDomains) {
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true,
                URI.create("acme://letsencrypt.org/staging"),
                "admin@example.test",
                List.of("example.test"),
                "ORION",
                Optional.of(new OrionMaterialReference("acme-account-v1", 1)),
                30,
                40,
                true,
                allowRequestedDomains);
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

    private static OrionKeyMaterial owner(InMemoryKeyMaterialContentStore store) throws Exception {
        return OrionKeyMaterial.open(
                store,
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                new SigningMaterialSet(SIGNING, List.of()),
                2048);
    }

    private static KeyMaterialDescriptor descriptor(String alias, KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias),
                purpose,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1),
                KeyMaterialScope.cluster(CLUSTER));
    }

    private static final class RecordingIssuer extends AcmeCertificateIssuer {
        private final boolean wrongKey;
        private AcmeCertificateIssueRequest lastRequest;

        private RecordingIssuer(boolean wrongKey) {
            super(new AcmeHttpChallengeService());
            this.wrongKey = wrongKey;
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
            lastRequest = request;
            try {
                KeyPair keyPair = wrongKey ? keyPair() : request.domainKeyPair();
                X509Certificate leaf = TestCertificateChain.selfSignedLeaf("example.test", keyPair);
                return new IssuedAcmeCertificate(request.domains(), List.of(leaf));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        private static KeyPair keyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
    }
}
