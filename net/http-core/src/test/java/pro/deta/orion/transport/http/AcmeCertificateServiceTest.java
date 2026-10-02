package pro.deta.orion.transport.http;

import java.util.Set;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.SSLContext;
import java.net.ServerSocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.AcmeKeyMaterial;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
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
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.OrionConfiguration;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
                    bootstrap, desiredState, owner.acme(), issuer, null);

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
                    bootstrap, desiredState, owner.acme(), new RecordingIssuer(false), null);

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
                    bootstrap(), desiredState(false), owner.acme(), new RecordingIssuer(false), null);

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
                    bootstrap(), desiredState(true), owner.acme(), issuer, null);

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
                    bootstrap(), desiredState(false), owner.acme(), new RecordingIssuer(true), null);

            assertThatThrownBy(() -> service.issue(AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessageContaining("store issued ACME certificate");
            assertThat(service.savedCertificate()).isEmpty();
        }
    }

    @Test
    void rejectsConcurrentIssuanceBeforeKeyAcquisitionOrIssuerEntryAndReopensAfterSuccess() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore());
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountingAcmeKeyMaterial keyMaterial = new CountingAcmeKeyMaterial(owner.acme());
            BlockingFirstIssuer issuer = new BlockingFirstIssuer();
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), keyMaterial, issuer, null);
            Future<IssuedAcmeCertificate> first = executor.submit(
                    () -> service.issue(AcmeCertificateService.IssueRequest.EMPTY));

            assertThat(issuer.awaitStarted()).isTrue();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThatThrownBy(
                        () -> service.issue(AcmeCertificateService.IssueRequest.EMPTY))
                        .isInstanceOf(AcmeCertificateService.IssuanceBusyException.class));
                service.maintainCertificate(Instant.now().plus(Duration.ofDays(90)), () -> {});
                assertThat(keyMaterial.acquireCalls()).isEqualTo(1);
                assertThat(issuer.issueCalls()).isEqualTo(1);
            } finally {
                issuer.release();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).certificateChain()).hasSize(1);
            assertThat(service.issue(AcmeCertificateService.IssueRequest.EMPTY).certificateChain()).hasSize(1);
            assertThat(keyMaterial.acquireCalls()).isEqualTo(2);
            assertThat(issuer.issueCalls()).isEqualTo(2);
        }
    }

    @Test
    void reopensIssuanceAdmissionAfterFailure() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            FailFirstIssuer issuer = new FailFirstIssuer();
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);

            assertThatThrownBy(() -> service.issue(AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessage("expected issuance failure");

            assertThat(service.issue(AcmeCertificateService.IssueRequest.EMPTY).certificateChain()).hasSize(1);
            assertThat(issuer.issueCalls()).isEqualTo(2);
        }
    }

    @Test
    void warnsForEachActualRenewalAttemptWithoutLoggingIdleChecks() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AcmeCertificateService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);
            X509Certificate initial = service.issue(AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            Instant due = Instant.parse(service.renewalStatus().nextAttempt());
            service.maintainCertificate(due.minusSeconds(1), () -> {});
            assertThat(appender.list).isEmpty();
            issuer.failure = new AcmeCertificateIssueException("private provider response");
            service.maintainCertificate(due, () -> {});
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("Starting ACME certificate renewal", "example.test",
                            initial.getNotAfter().toInstant().toString(), due.toString())
                    .doesNotContain("private provider response", "admin@example.test", "eab");
            service.maintainCertificate(due.plusSeconds(60), () -> {});
            assertThat(appender.list).hasSize(2);
            issuer.failure = null;
            service.maintainCertificate(due.plusSeconds(3600), () -> {});
            assertThat(appender.list).hasSize(3);
            assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getLast().getFormattedMessage())
                    .contains("Starting ACME certificate renewal", due.plusSeconds(3600).toString());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void renewsOnlyWhenDueAndReusesTheStoredAccount() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);
            service.maintainCertificate(Instant.now(), () -> {});
            assertThat(issuer.lastRequest).isNull();
            X509Certificate initial = service.issue(AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            KeyPair account = issuer.lastRequest.accountKeyPair();
            Instant due = initial.getNotAfter().toInstant().minus(
                    Duration.between(initial.getNotBefore().toInstant(), initial.getNotAfter().toInstant())
                            .dividedBy(3));
            service.maintainCertificate(due.minusSeconds(1), () -> {});
            assertThat(service.savedCertificate().orElseThrow().certificateChain().getFirst()).isEqualTo(initial);
            service.maintainCertificate(due, () -> {});
            assertThat(service.savedCertificate().orElseThrow().certificateChain().getFirst()).isNotEqualTo(initial);
            assertThat(issuer.lastRequest.accountKeyPair().getPublic()).isEqualTo(account.getPublic());
        }
    }

    @Test
    void retriesFailureAfterOneHourKeepsOldCertificateAndRetriesActivationWithoutReissuing() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);
            X509Certificate initial = service.issue(AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            Instant due = Instant.parse(service.renewalStatus().nextAttempt());
            issuer.failure = new AcmeCertificateIssueException("private provider response");
            service.maintainCertificate(due, () -> {});
            assertThat(service.savedCertificate().orElseThrow().certificateChain().getFirst()).isEqualTo(initial);
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            assertThat(service.renewalStatus().message()).doesNotContain("private provider response");
            assertThat(service.renewalStatus().nextAttempt()).isEqualTo(due.plusSeconds(3600).toString());
            assertThat(issuer.calls).isEqualTo(2);
            service.maintainCertificate(due.plusSeconds(3599), () -> {});
            assertThat(issuer.calls).isEqualTo(2);
            issuer.failure = null;
            service.maintainCertificate(due.plusSeconds(3600), () -> {
                throw new IllegalStateException("private TLS error");
            });
            assertThat(issuer.calls).isEqualTo(3);
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            assertThat(service.renewalStatus().activationError()).contains("Could not activate")
                    .doesNotContain("private TLS error");
            service.maintainCertificate(due.plusSeconds(3660), () -> {});
            assertThat(service.renewalStatus().activationError()).isEmpty();
            assertThat(issuer.calls).isEqualTo(3);
            service.stopMaintenance();
            service.maintainCertificate(due.plusSeconds(7200), () -> { throw new AssertionError(); });
            assertThat(issuer.calls).isEqualTo(3);
        }
    }

    @Test
    void disablesRenewalWhenConfigurationIsRemoved() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            OrionDesiredState desired = desiredState(false);
            AcmeCertificateService service = new AcmeCertificateService(bootstrap(), desired, owner.acme(), issuer, null);
            service.issue(AcmeCertificateService.IssueRequest.EMPTY);
            Instant due = Instant.parse(service.renewalStatus().nextAttempt());
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("removed"));
            service.maintainCertificate(due, () -> {});
            assertThat(issuer.calls).isEqualTo(1);
            assertThat(service.renewalStatus().state()).isEqualTo("disabled");
        }
    }

    @Test
    void activatesRenewedCertificateOnTheRunningHttpsListener() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            OrionConfiguration bootstrap = bootstrap();
            bootstrap.getTransport().getHttp().setEnabled(false);
            OrionDesiredState desired = desiredState(false);
            OrionHttpsConfiguration old = desired.current().document().system().https().orElseThrow();
            int availablePort;
            try (ServerSocket probe = new ServerSocket(0)) { availablePort = probe.getLocalPort(); }
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(true, "127.0.0.1", availablePort, old.publicUrl(),
                    old.identity(), old.serverIssuerTrustAnchor(), old.clientAuthentication(), List.of(), old.acme());
            desired.publish(new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.of(https), List.of(), List.of(), List.of()), List.of()), Optional.of("https"));
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap, desired, owner.acme(), new RecordingIssuer(false), null);
            X509Certificate initial = service.issue(AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            ObjectMapper mapper = new ObjectMapper();
            JettyHTTPServer server = new JettyHTTPServer(bootstrap, desired, owner.tls(),
                    new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of()),
                            new OrionHttpResponseWriter(mapper)), null, null);
            server.onStart();
            try {
                int port = server.boundHttpsPort();
                assertThat(servedCertificate(port)).isEqualTo(initial);
                Instant due = Instant.parse(service.renewalStatus().nextAttempt());
                service.maintainCertificate(due, server::reloadHttpsCertificate);
                X509Certificate renewed = service.savedCertificate().orElseThrow().certificateChain().getFirst();
                assertThat(renewed).isNotEqualTo(initial);
                assertThat(server.boundHttpsPort()).isEqualTo(port);
                assertThat(servedCertificate(port)).isEqualTo(renewed);
                assertThat(service.renewalStatus().activationError()).isEmpty();
            } finally {
                server.onStop();
            }
        }
    }

    private static X509Certificate servedCertificate(int port) throws Exception {
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (SSLSocket socket = (SSLSocket)
                client.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    @Test
    void lifecycleRunsRenewalAndCancelsAnOutstandingOrderBeforeInstallation() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch blocked = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(new AcmeHttpChallengeService()) {
                @Override
                public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
                    int call = calls.incrementAndGet();
                    try {
                        if (call > 1) {
                            started.countDown();
                            try {
                                blocked.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                // Simulates a transport that clears interruption before returning a certificate.
                            }
                        }
                        return new IssuedAcmeCertificate(request.domains(), List.of(
                                TestCertificateChain.selfSignedLeaf("example.test", request.domainKeyPair(),
                                        Instant.now().minus(Duration.ofDays(80)),
                                        Instant.now().plus(Duration.ofDays(10)))));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);
            X509Certificate initial = service.issue(AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            // A restart reconstructs the due date from the persisted certificate.
            AcmeCertificateService restarted = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);
            restarted.startMaintenance(() -> { throw new AssertionError("Stopped maintenance must not activate"); });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                restarted.stopMaintenance();
                blocked.countDown();
            }
            assertThat(calls).hasValue(2);
            assertThat(restarted.renewalStatus().state()).isEqualTo("stopped");
            assertThat(restarted.savedCertificate().orElseThrow().certificateChain().getFirst()).isEqualTo(initial);
        }
    }

    @Test
    void discardsCertificateIfSettingsChangeDuringIssuance() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore());
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            OrionDesiredState desired = desiredState(false);
            OrionDocument initial = desired.current().document();
            BlockingFirstIssuer issuer = new BlockingFirstIssuer();
            AcmeCertificateService service = new AcmeCertificateService(bootstrap(), desired, owner.acme(), issuer, null);
            Future<IssuedAcmeCertificate> issuance = executor.submit(
                    () -> service.issue(AcmeCertificateService.IssueRequest.EMPTY));
            assertThat(issuer.awaitStarted()).isTrue();
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("changed"));
            issuer.release();
            assertThatThrownBy(() -> issuance.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AcmeCertificateIssueException.class);
            desired.publish(initial, Optional.of("restored"));
            assertThat(service.savedCertificate()).isEmpty();
        }
    }

    @Test
    void capsRenewalLeadTimeAtThirtyDays() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            issuer.before = Instant.now().minus(Duration.ofDays(90));
            issuer.after = Instant.now().plus(Duration.ofDays(90));
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap(), desiredState(false), owner.acme(), issuer, null);
            X509Certificate initial = service.issue(AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            Instant due = initial.getNotAfter().toInstant().minus(Duration.ofDays(30));
            assertThat(service.renewalStatus().nextAttempt()).isEqualTo(due.toString());
            service.maintainCertificate(due.minusSeconds(1), () -> {});
            assertThat(issuer.calls).isEqualTo(1);
            service.maintainCertificate(due, () -> {});
            assertThat(issuer.calls).isEqualTo(2);
        }
    }

    @Test
    void startsAndStopsRenewalWithTheHttpTransport() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            OrionConfiguration bootstrap = bootstrap();
            bootstrap.getTransport().getHttp().setEnabled(true);
            bootstrap.getTransport().getHttp().setPort(0);
            OrionDesiredState desired = desiredState(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    bootstrap, desired, owner.acme(), new RecordingIssuer(false), null);
            service.issue(AcmeCertificateService.IssueRequest.EMPTY);
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            CountDownLatch checked = new CountDownLatch(1);
            ObjectMapper mapper = new ObjectMapper();
            JettyHTTPServer server = new JettyHTTPServer(bootstrap, desired, owner.tls(),
                    new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of()),
                            new OrionHttpResponseWriter(mapper)), null, null) {
                @Override
                void reloadHttpsCertificate() {
                    super.reloadHttpsCertificate();
                    checked.countDown();
                }
            };
            GitPackCleanupTask cleanup = new GitPackCleanupTask(new InMemoryNativeGitRepositoryProvider());
            JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(
                    () -> server, () -> service, () -> cleanup);
            try {
                assertThat(machine.start().failed()).isFalse();
                assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.renewalStatus().state()).isEqualTo("scheduled");
            } finally {
                assertThat(machine.stop().failed()).isFalse();
            }
            assertThat(server.isRunning()).isFalse();
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            assertThat(cleanup.status().state()).isEqualTo("stopped");
            bootstrap.getTransport().getHttp().setEnabled(false);
            JettyHTTPServerStateMachine disabled = new JettyHTTPServerStateMachine(() -> server,
                    () -> { throw new AssertionError("Disabled HTTP must not resolve ACME maintenance"); },
                    () -> { throw new AssertionError("Disabled HTTP must not resolve Git cleanup"); });
            assertThat(disabled.start().failed()).isFalse();
            assertThat(server.isRunning()).isFalse();
            assertThat(disabled.stop().failed()).isFalse();
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
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
                allowRequestedDomains, Optional.empty(), Optional.empty());
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
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.of(https), List.of(), List.of(), List.of()),
                List.of()), Optional.of("test-revision"));
        return desiredState;
    }

    private static OrionKeyMaterial owner(InMemoryKeyMaterialContentStore store) throws Exception {
        return OrionKeyMaterial.open(
                store,
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                new SigningMaterialSet(SIGNING, List.of()),
                2048,
                true);
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
        private RuntimeException failure;
        private int calls;
        private Instant before;
        private Instant after;

        private RecordingIssuer(boolean wrongKey) {
            super(new AcmeHttpChallengeService());
            this.wrongKey = wrongKey;
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
            calls++;
            if (failure != null) throw failure;
            lastRequest = request;
            try {
                KeyPair keyPair = wrongKey ? keyPair() : request.domainKeyPair();
                X509Certificate leaf = before == null ? TestCertificateChain.selfSignedLeaf("example.test", keyPair)
                        : TestCertificateChain.selfSignedLeaf("example.test", keyPair, before, after);
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

    private static final class BlockingFirstIssuer extends AcmeCertificateIssuer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger issueCalls = new AtomicInteger();

        private BlockingFirstIssuer() {
            super(new AcmeHttpChallengeService());
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
            if (issueCalls.incrementAndGet() == 1) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release the first issuance");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return certificateFor(request);
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private int issueCalls() {
            return issueCalls.get();
        }
    }

    private static final class FailFirstIssuer extends AcmeCertificateIssuer {
        private final AtomicInteger issueCalls = new AtomicInteger();

        private FailFirstIssuer() {
            super(new AcmeHttpChallengeService());
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
            if (issueCalls.incrementAndGet() == 1) {
                throw new AcmeCertificateIssueException("expected issuance failure");
            }
            return certificateFor(request);
        }

        private int issueCalls() {
            return issueCalls.get();
        }
    }

    private static final class CountingAcmeKeyMaterial implements AcmeKeyMaterialCapability {
        private final AcmeKeyMaterialCapability delegate;
        private final AtomicInteger acquireCalls = new AtomicInteger();

        private CountingAcmeKeyMaterial(AcmeKeyMaterialCapability delegate) {
            this.delegate = delegate;
        }

        @Override
        public AcmeKeyMaterial acquire(
                AcmeMaterialConfiguration configuration,
                int accountKeySize,
                int domainKeySize) throws IOException, GeneralSecurityException {
            acquireCalls.incrementAndGet();
            return delegate.acquire(configuration, accountKeySize, domainKeySize);
        }

        @Override
        public void installCertificateChain(
                AcmeMaterialConfiguration configuration,
                List<? extends Certificate> certificateChain,
                Optional<X509Certificate> issuerTrustAnchor) throws IOException, GeneralSecurityException {
            delegate.installCertificateChain(configuration, certificateChain, issuerTrustAnchor);
        }

        @Override
        public Optional<List<X509Certificate>> certificateChain(AcmeMaterialConfiguration configuration)
                throws GeneralSecurityException {
            return delegate.certificateChain(configuration);
        }

        @Override
        public Optional<X509Certificate> issuerTrustAnchor(AcmeMaterialConfiguration configuration)
                throws GeneralSecurityException {
            return delegate.issuerTrustAnchor(configuration);
        }

        private int acquireCalls() {
            return acquireCalls.get();
        }
    }

    private static IssuedAcmeCertificate certificateFor(AcmeCertificateIssueRequest request) {
        try {
            X509Certificate leaf = TestCertificateChain.selfSignedLeaf(
                    "example.test", request.domainKeyPair());
            return new IssuedAcmeCertificate(request.domains(), List.of(leaf));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
