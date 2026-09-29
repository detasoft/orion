package pro.deta.orion.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.keymaterial.AcmeMaterialConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;
import pro.deta.orion.test.integration.OrionTestRootAccess;
import pro.deta.orion.util.KeyUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

@EnabledIfSystemProperty(named = "external.services.playwright.enabled", matches = "true")
class PlaywrightExternalServicesIT {
    private static final String DIRECTORY_URL = "https://fixture.orion.test:9000/acme/acme/directory";

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void browserExercisesCertificateIssuanceRepositoriesAndExternalGitProxy() throws Exception {
        Path fixtureRoot = Path.of(System.getProperty("external.services.fixture.root"));
        Path caRoot = fixtureRoot.resolve(".state/step/certs/root_ca.crt");
        assertThat(caRoot).exists();

        OrionConfiguration configuration = serverConfiguration(tempDir.resolve("orion"));
        KeyPair rootKey = KeyUtils.generateRSAKeyPair().valueOrFailure("test root key");
        AcmeMaterialConfiguration material = acmeMaterial(configuration);
        try (TestServerIdentityMaterial identity = TestServerIdentityMaterial.open(configuration)) {
            identity.material().acme().acquire(material, 2048, 2048);
            OrionComponent component = TestRuntimeBootstrap.componentBuilder(
                            configuration, identity.capability(), identity.sshHostKeys())
                    .acmeKeyMaterialCapability(identity.material().acme())
                    .configurationMaterialCapability(identity.material().configurationMaterial())
                    .configurationCipherCapability(identity.material().configurationCipher())
                    .tlsCapability(identity.material().tls())
                    .build();
            OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                lifecycle.waitForStarting();
                OrionAccessControlServiceImpl accessControl = component.orionAccessControlService();
                OrionTestRootAccess.enroll(accessControl, rootKey.getPublic());
                configureAcme(component, accessControl, configuration);
                String token = OrionTestRootAccess.issueToken(accessControl, rootKey.getPublic(), 600);
                runPlaywright(fixtureRoot, caRoot, token);
            } finally {
                lifecycle.shutdownApplication();
                lifecycle.waitForShutdown();
            }
        }
        try (TestServerIdentityMaterial reopened = TestServerIdentityMaterial.open(configuration)) {
            assertThat(reopened.material().acme().certificateChain(material)).isPresent();
        }
    }

    static OrionConfiguration serverConfiguration(Path orionRoot) throws IOException {
        return RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot, options -> {
            options.getTransport().getHttp().setAddress("0.0.0.0");
            options.getTransport().getHttp().setPort(8000);
            options.getTransport().getGit().setEnabled(true);
            options.getTransport().getGit().setAddress("0.0.0.0");
            options.getTransport().getGit().setPort(9419);
            options.getTransport().getSsh().setEnabled(true);
            options.getTransport().getSsh().setAddress("0.0.0.0");
            options.getTransport().getSsh().setPort(8022);
        });
    }

    private static AcmeMaterialConfiguration acmeMaterial(OrionConfiguration configuration) {
        KeyMaterialScope scope = KeyMaterialScope.cluster(
                configuration.getBootstrap().getKeyMaterial().getClusterId());
        return new AcmeMaterialConfiguration(
                new KeyMaterialDescriptor(new KeyMaterialAlias("acme-account"),
                        KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA,
                        new KeyMaterialVersion(1), scope),
                new KeyMaterialDescriptor(new KeyMaterialAlias("acme-identity"),
                        KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA,
                        new KeyMaterialVersion(1), scope),
                Optional.empty());
    }

    private static void configureAcme(OrionComponent component,
            OrionAccessControlServiceImpl accessControl, OrionConfiguration configuration) throws Exception {
        String revision = component.nativeGitRepositoryProvider().find("orion")
                .valueOrFailure("test configuration repository")
                .loadFiles(configuration.getBootstrap().getAccessControl().selectedRef(), List.of("orion.xml"))
                .version().orElseThrow();
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true, URI.create(DIRECTORY_URL), "orion@orion.test", List.of("orion.test"), "Orion",
                Optional.of(new OrionMaterialReference("acme-account", 1)), 90, 120, true, false,
                Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                false, "0.0.0.0", 9443, URI.create("https://orion.test:9443"),
                Optional.of(new OrionMaterialReference("acme-identity", 1)), Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.of(acme));
        accessControl.updatePrimaryConfiguration(revision, document -> new OrionDocument(
                        new OrionDocument.SystemConfiguration(
                                document.system().accessControl(), Optional.of(https),
                                document.system().secrets(), document.system().proxies()),
                        document.organizations()),
                new AccessControlSaveRequest("configure test ACME", UserEmail.EMPTY));
    }

    private void runPlaywright(Path fixtureRoot, Path caRoot, String token) throws Exception {
        SSLContext previousContext = SSLContext.getDefault();
        SSLSocketFactory previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        try {
            SSLContext fixtureContext = trustFixtureCa(caRoot);
            SSLContext.setDefault(fixtureContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(fixtureContext.getSocketFactory());
            Path output = fixtureRoot.getParent().resolve("integration-test/target/playwright.log");
            ProcessBuilder command = new ProcessBuilder("npm", "test")
                    .directory(fixtureRoot.getParent().resolve("integration-test/playwright").toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            command.environment().put("ORION_HTTP_URL", "http://127.0.0.1:8000");
            command.environment().put("ORION_TOKEN", token);
            Process process = command.start();
            try {
                if (!process.waitFor(4, TimeUnit.MINUTES)) {
                    throw new AssertionError("Playwright external-services tests timed out: " + output);
                }
                assertThat(process.exitValue()).as(Files.readString(output)).isZero();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
            SSLContext.setDefault(previousContext);
        }
    }

    private static SSLContext trustFixtureCa(Path caRoot) throws Exception {
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        X509Certificate root;
        try (InputStream input = Files.newInputStream(caRoot)) {
            root = (X509Certificate) certificates.generateCertificate(input);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("fixture", root);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }
}
