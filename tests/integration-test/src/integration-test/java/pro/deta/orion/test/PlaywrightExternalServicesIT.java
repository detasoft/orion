package pro.deta.orion.test;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.TeamId;
import pro.deta.orion.test.integration.OrionTestRootAccess;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.VcRevision;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.ArrayList;
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
    void browserExercisesCertificateIssuanceRepositoriesGitProxyAndOidc() throws Exception {
        Path fixtureRoot = Path.of(System.getProperty("external.services.fixture.root"));
        Path caRoot = fixtureRoot.resolve(".state/step/certs/root_ca.crt");
        assertThat(caRoot).exists();

        BootstrapConfiguration configuration = serverConfiguration(tempDir.resolve("orion"));
        KeyPair rootKey = KeyUtils.generateRSAKeyPair().valueOrFailure("test root key");
        KeyMaterialId account = new KeyMaterialId("acme-account", 1);
        KeyMaterialId certificateIdentity = new KeyMaterialId("acme-identity", 1);
        try (TestServerIdentityMaterial identity = TestServerIdentityMaterial.open(configuration)) {
            identity.material().withModification(identity.material().revision(), modification -> {
                modification.generateKey("acme-account", Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey("acme-identity", Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            runPlaywright(configuration, identity, rootKey, fixtureRoot, caRoot);
        }
        try (TestServerIdentityMaterial reopened = TestServerIdentityMaterial.open(configuration)) {
            KeyMaterialId selected = null;
            for (KeyMaterialId id : reopened.material().inventory()) {
                if (id.alias().equals(certificateIdentity.alias())) selected = id;
            }
            assertThat(selected).isNotNull();
            assertThat(reopened.material().read(selected).issuedCertificateChain()).isPresent();
        }
    }

    static BootstrapConfiguration serverConfiguration(Path orionRoot) throws IOException {
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

    private static void configureAcme(OrionComponent component, BootstrapConfiguration configuration)
            throws Exception {
        String revision = component.nativeGitRepositoryProvider().find("orion")
                .valueOrFailure("test configuration repository")
                .refs().get(configuration.getBootstrap().getConfiguration().selectedRef());
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true, URI.create(DIRECTORY_URL), "orion@orion.test", List.of("orion.test"), "Orion",
                Optional.of(new KeyMaterialId("acme-account", 1)), 90, 120, true, false,
                Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                false, "0.0.0.0", 9443, URI.create("https://orion.test:9443"),
                Optional.of(new KeyMaterialId("acme-identity", 1)), Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.of(acme));
        TestRuntimeBootstrap.updateConfiguration(
                component,
                new VcRevision.Stored(revision),
                new pro.deta.orion.internal.ChangeMetadata(UserEmail.EMPTY, "configure test ACME"),
                document ->
                        new OrionDocument(
                                new OrionDocument.SystemConfiguration(
                                        document.system().accessControl(),
                                        Optional.of(https),
                                        document.system().secrets(),
                                        document.system().proxies(),
                                        document.system().connections(),
                                        document.system().oidcProviders(), document.system().invitations()),
                                document.organizations()));
    }

    private void runPlaywright(BootstrapConfiguration configuration, TestServerIdentityMaterial identity,
            KeyPair rootKey, Path fixtureRoot, Path caRoot) throws Exception {
        SSLContext previousContext = SSLContext.getDefault();
        SSLSocketFactory previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        Path restartDirectory = Files.createDirectory(tempDir.resolve("oidc-restarts"));
        Path browserOutput = fixtureRoot.getParent().resolve("integration-test/target/browser-oidc-restart.log");
        Process browser = null;
        try {
            SSLContext fixtureContext = trustFixtureCa(caRoot);
            SSLContext.setDefault(fixtureContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(fixtureContext.getSocketFactory());
            for (String phase : List.of("certificate", "browser", "refresh", "revoked")) {
                boolean issueCertificate = phase.equals("certificate");
                OrionComponent component = TestRuntimeBootstrap.componentBuilder(
                                configuration, identity.material(), identity.sshHostKeys())
                        .keyMaterial(identity.material())
                        .build();
                OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
                try {
                    assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                    lifecycle.waitForStarting();
                    OrionAccessControlServiceImpl accessControl = component.orionAccessControlService();
                    if (issueCertificate) {
                        TestBearerTokens.enrollRootKey(accessControl, component.configurationEditor(), rootKey);
                        configureAcme(component, configuration);
                        configureOrganizations(component);
                    }
                    String token = OrionTestRootAccess.issueToken(accessControl, rootKey.getPublic(), 600);
                    assertThat(accessControl.verifyToken(token.getBytes(StandardCharsets.UTF_8)))
                            .isInstanceOf(TokenAuthenticationResult.Success.class);
                    RuntimeHttpTestSupport.HttpResponse identityResponse = RuntimeHttpTestSupport.request(
                            "GET", new URL("http://127.0.0.1:8000/api/auth/me"), TestBearerTokens.bearer(token));
                    assertThat(identityResponse.status()).isEqualTo(200);
                    assertThat(identityResponse.body()).contains("\"admin\":true");
                    if (issueCertificate) {
                        runPlaywrightGoal(fixtureRoot, token, "browser-acme-test", "");
                    } else if (phase.equals("browser")) {
                        browser = startPlaywrightGoal(fixtureRoot, token, "browser-test",
                                "invited organization member", browserOutput);
                        awaitRestartRequest(browser, restartDirectory.resolve("restart-1.request"), browserOutput);
                    } else if (phase.equals("refresh")) {
                        Files.writeString(restartDirectory.resolve("restart-1.ready"), "ready");
                        awaitRestartRequest(browser, restartDirectory.resolve("restart-2.request"), browserOutput);
                    } else {
                        Files.writeString(restartDirectory.resolve("restart-2.ready"), "ready");
                        awaitPlaywrightExit(browser, browserOutput);
                        // The Git proxy scenario removes its upstream; run it after the final restart.
                        runPlaywrightGoal(fixtureRoot, token, "browser-test",
                                "^(?!.*invited organization member).*");
                    }
                    if (!issueCertificate) {
                        List<OrionDocument.Organization> organizations = component.configurationEditor().current().result()
                                        .valueOrFailure("Cannot read configuration document")
                                        .organizations();
                        assertThat(organizations).extracting(organization -> organization.id().value())
                                .contains("oidc-acme", "oidc-other");
                        for (OrionDocument.Organization organization : organizations) {
                            if (organization.id().value().equals("oidc-acme")) {
                                assertThat(organization.users()).singleElement().satisfies(user -> {
                                    assertThat(user.first()).isEqualTo("Fixture");
                                    assertThat(user.last()).isEqualTo("Member");
                                });
                                assertThat(organization.invitations()).isEmpty();
                            } else if (organization.id().value().equals("oidc-other")) {
                                assertThat(organization.users()).isEmpty();
                            }
                        }
                    }
                    if (issueCertificate) enableHttps(component);
                } finally {
                    lifecycle.shutdownApplication();
                    lifecycle.waitForShutdown();
                }
            }
        } finally {
            if (browser != null && browser.isAlive()) browser.destroyForcibly();
            HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
            SSLContext.setDefault(previousContext);
        }
    }

    private static void configureOrganizations(OrionComponent component) {
        TestRuntimeBootstrap.updateConfiguration(
                component,
                component.configurationEditor().current().revision(),
                new pro.deta.orion.internal.ChangeMetadata(
                        UserEmail.EMPTY, "configure browser test organizations"),
                document -> {
                    List<OrionDocument.Organization> organizations =
                            new ArrayList<>(document.organizations());
                    for (String id : List.of("oidc-acme", "oidc-other")) {
                        OrionDocument.Repository repository =
                                new OrionDocument.Repository(
                                        "repository",
                                        "Browser test",
                                        OrionDocument.Repository.DEFAULT_BRANCH,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        Optional.empty());
                        OrionDocument.Team team =
                                new OrionDocument.Team(
                                        new TeamId("team"),
                                        "Browser test",
                                        List.of(),
                                        List.of(),
                                        List.of(repository));
                        organizations.add(
                                new OrionDocument.Organization(
                                        new OrganizationId(id),
                                        id,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(team),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()));
                    }
                    return new OrionDocument(document.system(), organizations);
                });
    }

    private static void enableHttps(OrionComponent component) {
        TestRuntimeBootstrap.updateConfiguration(
                component,
                component.configurationEditor().current().revision(),
                new pro.deta.orion.internal.ChangeMetadata(UserEmail.EMPTY, "enable test HTTPS"),
                document -> {
                    OrionDocument.SystemConfiguration system = document.system();
                    OrionHttpsConfiguration https = system.https().orElseThrow();
                    OrionHttpsConfiguration enabled =
                            new OrionHttpsConfiguration(
                                    true,
                                    https.address(),
                                    https.port(),
                                    https.publicUrl(),
                                    https.identity(),
                                    https.serverIssuerTrustAnchor(),
                                    https.clientAuthentication(),
                                    https.clientTrustAnchors(),
                                    https.acme());
                    return new OrionDocument(
                            new OrionDocument.SystemConfiguration(
                                    system.accessControl(),
                                    Optional.of(enabled),
                                    system.secrets(),
                                    system.proxies(),
                                    system.connections(),
                                    system.oidcProviders(), system.invitations()),
                            document.organizations());
                });
    }

    private void runPlaywrightGoal(Path fixtureRoot, String token, String goal, String filter) throws Exception {
        Path output = fixtureRoot.getParent().resolve("integration-test/target/" + goal + ".log");
        Process process = startPlaywrightGoal(fixtureRoot, token, goal, filter, output);
        try {
            awaitPlaywrightExit(process, output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Process startPlaywrightGoal(Path fixtureRoot, String token, String goal, String filter, Path output)
            throws IOException {
        ProcessBuilder command = new ProcessBuilder(
                "make", "-s", goal, "URL=http://127.0.0.1:8000", "TEST=" + filter)
                .directory(fixtureRoot.normalize().getParent().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        command.environment().put("ORION_TOKEN", token);
        command.environment().put("ORION_OIDC_RESTART_DIRECTORY", tempDir.resolve("oidc-restarts").toString());
        return command.start();
    }

    private static void awaitPlaywrightExit(Process process, Path output) throws Exception {
        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            throw new AssertionError("Playwright external-services tests timed out: " + output);
        }
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
    }

    private static void awaitRestartRequest(Process process, Path marker, Path output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (!Files.exists(marker)) {
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Browser exited before requesting restart: " + Files.readString(output));
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Browser did not request restart: " + marker);
            }
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
