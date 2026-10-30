package pro.deta.orion.acme;

import pro.deta.orion.storage.BlobStorage;
import java.util.concurrent.atomic.AtomicReference;
import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import pro.deta.orion.test.certificates.TestCertificateChain;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.transport.http.OrionHttpRouteServlet;
import pro.deta.orion.transport.http.OrionHttpRouteRegistry;
import pro.deta.orion.transport.http.OrionHttpResponseWriter;
import pro.deta.orion.transport.http.GitPackCleanupTask;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import pro.deta.orion.test.integration.TestConfiguration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import java.util.concurrent.ScheduledFuture;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.util.OrionUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime events =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final KeyMaterialDescriptor ACCOUNT = descriptor(
            "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT);
    private static final KeyMaterialDescriptor IDENTITY = descriptor(
            "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY);
    private static final KeyMaterialDescriptor ROOT = new KeyMaterialDescriptor(
            "issuer-root-v1", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, 1);

    @TempDir
    private Path tempDir;

    @Test
    void preparesBothKeysOnlyAfterTheStoreAcceptsThem() throws Exception {
        class ObservingStore extends InMemoryBlobStorage {
            private OrionKeyMaterial material;

            @Override
            public synchronized String write(BlobStorage.Modification modification) throws IOException {
                byte[] bytes = modification.bytes();
                String expectedVersion = modification.expectedRevision();
                if (material != null) {
                    try {
                        assertThat(material.contains(ACCOUNT)).isFalse();
                        assertThat(material.contains(IDENTITY)).isFalse();
                    } catch (GeneralSecurityException failure) {
                        throw new IOException(failure);
                    }
                }
                return super.write(new BlobStorage.Modification(expectedVersion, bytes));
            }
        }
        ObservingStore store = new ObservingStore();
        TestConfiguration desired = desiredState(false);
        try (OrionKeyMaterial material = owner(store)) {
            store.material = material;
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), material, null, null, events.executor());
            saveSettings(service);
            assertThat(material.contains(ACCOUNT)).isTrue();
            assertThat(material.contains(IDENTITY)).isTrue();
            assertThat(material.revision()).isEqualTo("1");
            byte[] before = store.load().bytes();
            saveSettings(service);
            assertThat(store.load().bytes()).isEqualTo(before);
        }
    }

    @Test
    void publishesCertificateAndRootTogetherAndReusesTheKeysAfterReopening() throws Exception {
        class ObservingStore extends InMemoryBlobStorage {
            private OrionKeyMaterial material;

            @Override
            public synchronized String write(BlobStorage.Modification modification) throws IOException {
                byte[] bytes = modification.bytes();
                String expectedVersion = modification.expectedRevision();
                if (material != null) {
                    try {
                        assertThat(material.read(IDENTITY.id()).issuedCertificateChain()).isEmpty();
                        assertThat(material.contains(ROOT)).isFalse();
                    } catch (GeneralSecurityException failure) {
                        throw new IOException(failure);
                    }
                }
                return super.write(new BlobStorage.Modification(expectedVersion, bytes));
            }
        }
        ObservingStore store = new ObservingStore();
        TestConfiguration desired = desiredState(false,
                Optional.of(new KeyMaterialId("issuer-root-v1", 1)));
        RecordingIssuer issuer = new RecordingIssuer(false);
        issuer.root = TestCertificateChain.root("Issuer root");
        byte[] accountPublic;
        byte[] identityPublic;
        try (OrionKeyMaterial material = owner(store)) {
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), material, issuer, null, events.executor());
            saveSettings(service);
            store.material = material;
            IssuedAcmeCertificate issued = issue(service, AcmeCertificateService.IssueRequest.EMPTY);

            assertThat(issued.certificateChain()).hasSize(1);
            assertThat(material.read(new KeyMaterialId(IDENTITY.alias(), 2)).certificateChain())
                    .containsExactlyElementsOf(issued.certificateChain());
            assertThat(material.read(ROOT.id()).trustedCertificate()).isEqualTo(issuer.root.certificate());
            assertThat(material.revision()).isEqualTo("2");
            accountPublic = issuer.lastRequest.accountKeyPair().getPublic().getEncoded();
            identityPublic = issuer.lastRequest.domainKeyPair().getPublic().getEncoded();
            store.material = null;
        }
        try (OrionKeyMaterial reloaded = owner(store)) {
            issuer.omitRoot = true;
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), reloaded, issuer, null, events.executor());
            assertThat(service.savedCertificate().orElseThrow().certificateChain()).hasSize(1);
            issue(service, AcmeCertificateService.IssueRequest.EMPTY);
            assertThat(issuer.lastRequest.accountKeyPair().getPublic().getEncoded()).isEqualTo(accountPublic);
            assertThat(issuer.lastRequest.domainKeyPair().getPublic().getEncoded()).isEqualTo(identityPublic);
            assertThat(reloaded.revision()).isEqualTo("3");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCertificateWriteRecoversTheChainAndRootAsOneVersion(boolean committed) throws Exception {
        class FailingStore extends InMemoryBlobStorage {
            private boolean fail;

            @Override
            public synchronized String write(BlobStorage.Modification modification) throws IOException {
                byte[] bytes = modification.bytes();
                String expectedVersion = modification.expectedRevision();
                if (fail) {
                    if (committed) super.write(new BlobStorage.Modification(expectedVersion, bytes));
                    throw new IOException("certificate save failed");
                }
                return super.write(new BlobStorage.Modification(expectedVersion, bytes));
            }
        }
        FailingStore store = new FailingStore();
        TestConfiguration desired = desiredState(false,
                Optional.of(new KeyMaterialId("issuer-root-v1", 1)));
        TestCertificateChain.Authority originalRoot = TestCertificateChain.root("Original root");
        TestCertificateChain.Authority replacementRoot = TestCertificateChain.root("Replacement root");
        RecordingIssuer issuer = new RecordingIssuer(false);
        issuer.root = originalRoot;
        X509Certificate original;
        try (OrionKeyMaterial material = owner(store)) {
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), material, issuer, null, events.executor());
            original = issue(service, AcmeCertificateService.IssueRequest.EMPTY).certificateChain().getFirst();
            store.fail = true;
            issuer.root = replacementRoot;
            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessage("Cannot store issued ACME certificate");
            assertThatThrownBy(() -> material.read(IDENTITY.id()).certificateChain())
                    .isInstanceOf(IllegalStateException.class);
        }
        store.fail = false;
        try (OrionKeyMaterial reloaded = owner(store)) {
            X509Certificate expectedRoot =
                    committed ? replacementRoot.certificate() : originalRoot.certificate();
            X509Certificate loaded = reloaded.read(new KeyMaterialId(IDENTITY.alias(), committed ? 3 : 2))
                    .certificateChain().getFirst();
            loaded.verify(expectedRoot.getPublicKey());
            assertThat(reloaded.read(new KeyMaterialId(ROOT.alias(), committed ? 2 : 1)).trustedCertificate())
                    .isEqualTo(expectedRoot);
            assertThat(reloaded.revision()).isEqualTo(committed ? "3" : "2");
            if (committed) assertThat(loaded).isNotEqualTo(original);
            else assertThat(loaded).isEqualTo(original);
        }
    }

    @Test
    void savesTheCertificateAfterAnIndependentMaterialChangeDuringIssuance() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = owner(store)) {
            KeyMaterialDescriptor other = descriptor("other-account", KeyMaterialPurpose.ACME_ACCOUNT);
            AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(new AcmeHttpChallengeService()) {
                @Override
                public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
                    try {
                        assertThat(material.contains(ACCOUNT)).isTrue();
                        assertThat(material.contains(IDENTITY)).isTrue();
                        material.withModification(material.revision(), modification -> {
                            modification.generateKey(other, 2048);
                            modification.apply();
                        });
                    } catch (IOException | GeneralSecurityException failure) {
                        throw new AssertionError(failure);
                    }
                    return certificateFor(request);
                }
            };
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), material, issuer, null, events.executor());
            issue(service, AcmeCertificateService.IssueRequest.EMPTY);
            assertThat(material.contains(other)).isTrue();
            assertThat(service.savedCertificate()).isPresent();
            assertThat(material.revision()).isEqualTo("3");
        }
    }

    @Test
    void rejectsAnAccountAliasOwnedByAnotherPurposeBeforeContactingTheCa() throws Exception {
        try (OrionKeyMaterial material = owner(new InMemoryBlobStorage())) {
            KeyMaterialDescriptor signing = descriptor(ACCOUNT.alias(), KeyMaterialPurpose.SERVER_SIGNING);
            material.withModification(material.revision(), modification -> {
                modification.generateKey(signing, 2048);
                modification.apply();
            });
            String version = material.revision();
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), material, issuer, null, events.executor());
            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessage("Cannot acquire ACME key material");
            assertThat(material.contains(signing)).isTrue();
            assertThat(material.contains(IDENTITY)).isFalse();
            assertThat(material.revision()).isEqualTo(version);
            assertThat(issuer.calls).isZero();
        }
    }

    @Test
    void rejectsMismatchedIssuerMetadataBeforeGeneratingKeysOrContactingTheCa() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        RecordingIssuer issuer = new RecordingIssuer(false);
        issuer.root = TestCertificateChain.root("Root");
        try (OrionKeyMaterial material = owner(store)) {
            material.withModification(material.revision(), modification -> {
                KeyMaterialDescriptor mismatched = new KeyMaterialDescriptor(
                        ROOT.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR), ROOT.algorithm(), 1);
                modification.setTrustedCertificate(mismatched, issuer.root.certificate());
                modification.apply();
            });
            AcmeCertificateService service = new AcmeCertificateService(desiredState(false,
                    Optional.of(new KeyMaterialId("issuer-root-v1", 2))).editor(),
                    material, issuer, null, events.executor());
            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessage("Cannot acquire ACME key material");
            assertThat(material.contains(ACCOUNT)).isFalse();
            assertThat(material.contains(IDENTITY)).isFalse();
            assertThat(material.revision()).isEqualTo("1");
            assertThat(issuer.calls).isZero();
        }
    }

    @Test
    void rejectsAnIssuerAliasThatIsAlsoAnAcmeKeyBeforeContactingTheCa() throws Exception {
        try (OrionKeyMaterial material = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(desiredState(false,
                    Optional.of(new KeyMaterialId("acme-account-v1", 1))).editor(),
                    material, issuer, null, events.executor());
            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(material.inventory()).isEmpty();
            assertThat(material.revision()).isNull();
            assertThat(issuer.calls).isZero();
        }
    }

    @Test
    void issuesFromDesiredStatePersistsInMaterialStoreAndReloads() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        TestConfiguration desiredState = desiredState(false);

        try (OrionKeyMaterial owner = owner(store)) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState.editor(), owner, issuer, null, events.executor());

            IssuedAcmeCertificate certificate = issue(service, AcmeCertificateService.IssueRequest.EMPTY);

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
                    desiredState.editor(), owner, new RecordingIssuer(false), null, events.executor());

            assertThat(restarted.savedCertificate().orElseThrow().certificateChain()).hasSize(1);
        }

    }

    @Test
    void rejectsRequestedDomainsUnlessDesiredStateAllowsIt() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, new RecordingIssuer(false), null, events.executor());

            assertThatThrownBy(() -> issue(service, new AcmeCertificateService.IssueRequest(
                    null, null, List.of("other.example.test"), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Requested ACME domains are not allowed");
        }
    }

    @Test
    void allowsRequestedDomainsWhenDesiredStateAllowsIt() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(true).editor(), owner, issuer, null, events.executor());

            issue(service, new AcmeCertificateService.IssueRequest(
                    null, null, List.of("other.example.test"), null, null, null, null));

            assertThat(issuer.lastRequest.domains()).containsExactly("other.example.test");
        }
    }

    @Test
    void invalidIssuedChainDoesNotBecomeSavedMaterial() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial owner = owner(store)) {
            RecordingIssuer issuer = new RecordingIssuer(true);
            issuer.root = TestCertificateChain.root("Root for invalid chain");
            AcmeCertificateService service = new AcmeCertificateService(desiredState(false,
                    Optional.of(new KeyMaterialId("issuer-root-v1", 1))).editor(),
                    owner, issuer, null, events.executor());

            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessageContaining("store issued ACME certificate");
            assertThat(service.savedCertificate()).isEmpty();
            assertThat(owner.contains(ROOT)).isFalse();
            assertThat(owner.revision()).isEqualTo("1");
        }
    }

    @Test
    void rejectsConcurrentIssuanceWithoutChangingStoredMaterialAndReopensAfterSuccess() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial owner = owner(store);
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            BlockingFirstIssuer issuer = new BlockingFirstIssuer();
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, issuer, null, events.executor());
            Future<IssuedAcmeCertificate> first = executor.submit(
                    () -> issue(service, AcmeCertificateService.IssueRequest.EMPTY));

            assertThat(issuer.awaitStarted()).isTrue();
            byte[] before = store.load().bytes();
            String beforeVersion = owner.revision();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThatThrownBy(
                        () -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                        .isInstanceOf(AcmeCertificateService.IssuanceBusyException.class));
                maintain(service, Instant.now().plus(Duration.ofDays(90)));
                assertThat(store.load().bytes()).isEqualTo(before);
                assertThat(owner.revision()).isEqualTo(beforeVersion);
                assertThat(issuer.issueCalls()).isEqualTo(1);
            } finally {
                issuer.release();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).certificateChain()).hasSize(1);
            assertThat(issue(service, AcmeCertificateService.IssueRequest.EMPTY).certificateChain()).hasSize(1);
            assertThat(issuer.issueCalls()).isEqualTo(2);
        }
    }

    @Test
    void reopensIssuanceAdmissionAfterFailure() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            FailFirstIssuer issuer = new FailFirstIssuer();
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, issuer, null, events.executor());

            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessage("expected issuance failure");

            assertThat(issue(service, AcmeCertificateService.IssueRequest.EMPTY).certificateChain()).hasSize(1);
            assertThat(issuer.issueCalls()).isEqualTo(2);
        }
    }

    @Test
    void warnsForEachActualRenewalAttemptWithoutLoggingIdleChecks() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AcmeCertificateService.class);
        Thread testThread = Thread.currentThread();
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (Thread.currentThread() == testThread && event.getLevel().isGreaterOrEqual(Level.WARN)) {
                    super.append(event);
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, issuer, null, events.executor());
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            Instant due = Instant.parse(service.renewalStatus().nextAttempt());
            maintain(service, due.minusSeconds(1));
            assertThat(appender.list).isEmpty();
            issuer.failure = new AcmeCertificateIssueException("private provider response");
            maintain(service, due);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("Starting ACME certificate renewal", "example.test",
                            initial.getNotAfter().toInstant().toString(), due.toString())
                    .doesNotContain("private provider response", "admin@example.test", "eab");
            maintain(service, due.plusSeconds(60));
            assertThat(appender.list).hasSize(2);
            issuer.failure = null;
            maintain(service, due.plusSeconds(3600));
            assertThat(appender.list).hasSize(3);
            assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getLast().getFormattedMessage())
                    .contains("Starting ACME certificate renewal", due.plusSeconds(3600).toString());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void renewsOnlyWhenDueAndReusesTheStoredAccount() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, issuer, null, events.executor());
            maintain(service, Instant.now());
            assertThat(issuer.lastRequest).isNull();
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            KeyPair account = issuer.lastRequest.accountKeyPair();
            Instant due = initial.getNotAfter().toInstant().minus(
                    Duration.between(initial.getNotBefore().toInstant(), initial.getNotAfter().toInstant())
                            .dividedBy(3));
            maintain(service, due.minusSeconds(1));
            assertThat(service.savedCertificate().orElseThrow().certificateChain().getFirst()).isEqualTo(initial);
            maintain(service, due);
            assertThat(service.savedCertificate().orElseThrow().certificateChain().getFirst()).isNotEqualTo(initial);
            assertThat(issuer.lastRequest.accountKeyPair().getPublic()).isEqualTo(account.getPublic());
        }
    }

    @Test
    void retriesFailureAfterOneHourAndKeepsOldCertificate() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, issuer, null, events.executor());
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            Instant due = Instant.parse(service.renewalStatus().nextAttempt());
            issuer.failure = new AcmeCertificateIssueException("private provider response");
            maintain(service, due);
            assertThat(service.savedCertificate().orElseThrow().certificateChain().getFirst()).isEqualTo(initial);
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            assertThat(service.renewalStatus().message()).doesNotContain("private provider response");
            assertThat(service.renewalStatus().nextAttempt()).isEqualTo(due.plusSeconds(3600).toString());
            assertThat(issuer.calls).isEqualTo(2);
            maintain(service, due.plusSeconds(3599));
            assertThat(issuer.calls).isEqualTo(2);
            issuer.failure = null;
            maintain(service, due.plusSeconds(3600));
            assertThat(issuer.calls).isEqualTo(3);
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            maintain(service, due.plusSeconds(3660));
            assertThat(issuer.calls).isEqualTo(3);
            service.onStop();
            maintain(service, due.plusSeconds(7200));
            assertThat(issuer.calls).isEqualTo(3);
        }
    }

    @Test
    void disablesRenewalWhenConfigurationIsRemoved() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            TestConfiguration desired = desiredState(false);
            AcmeCertificateService service =
                    new AcmeCertificateService(desired.editor(), owner, issuer, null, events.executor());
            issue(service, AcmeCertificateService.IssueRequest.EMPTY);
            Instant due = Instant.parse(service.renewalStatus().nextAttempt());
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("removed"));
            maintain(service, due);
            assertThat(issuer.calls).isEqualTo(1);
            assertThat(service.renewalStatus().state()).isEqualTo("disabled");
        }
    }

    @Test
    void activatesRenewedCertificateOnTheRunningHttpsListener() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage(),
                events.events())) {
            BootstrapConfiguration bootstrap = bootstrap();
            bootstrap.getTransport().getHttp().setEnabled(false);
            TestConfiguration desired = desiredState(false);
            OrionHttpsConfiguration old =
                    desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document")
                            .system().https().orElseThrow();
            int availablePort;
            try (ServerSocket probe = new ServerSocket(0)) { availablePort = probe.getLocalPort(); }
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(true, "127.0.0.1", availablePort, old.publicUrl(),
                    old.identity(), old.serverIssuerTrustAnchor(), old.clientAuthentication(), List.of(), old.acme());
            desired.publish(new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.of(https), List.of(), List.of(), List.of(),
                    List.of(), List.of()), List.of()), Optional.of("https"));
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, new RecordingIssuer(false), null, events.executor());
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            ObjectMapper mapper = new ObjectMapper();
            JettyHTTPServer server = new JettyHTTPServer(bootstrap, desired.editor(), owner,
                    new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of()),
                            new OrionHttpResponseWriter(mapper)), null, null);
            NativeGitRepositoryProvider repositories = NativeGitRepositoryProvider.inMemory(events.events());
            GitPackCleanupTask cleanup = new GitPackCleanupTask(repositories);
            JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(() -> server,
                () -> cleanup, events.events(), events.executor());
            assertThat(machine.start().failed()).isFalse();
            try {
                int port = server.boundHttpsPort();
                assertThat(servedCertificate(port)).isEqualTo(initial);
                Instant due = Instant.parse(service.renewalStatus().nextAttempt());
                maintain(service, due);
                X509Certificate renewed = service.savedCertificate().orElseThrow().certificateChain().getFirst();
                assertThat(renewed).isNotEqualTo(initial);
                assertThat(server.boundHttpsPort()).isEqualTo(port);
                events.awaitEvents();
                assertThat(OrionUtils.waitForCondition(() -> {
                    try {
                        return servedCertificate(port).equals(renewed);
                    } catch (Exception failure) {
                        return false;
                    }
                })).isTrue();
                assertThat(servedCertificate(port)).isEqualTo(renewed);
                assertThat(server.certificateActivationError()).isEmpty();
            } finally {
                assertThat(machine.stop().failed()).isFalse();
                repositories.close();
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
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
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
            TestConfiguration desired = desiredState(false);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, issuer, null, events.executor());
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            // A restart reconstructs the due date from the persisted certificate.
            AcmeCertificateService restarted = new AcmeCertificateService(
                    desired.editor(), owner, issuer, null, events.executor());
            restarted.onStart();
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                restarted.onStop();
                blocked.countDown();
            }
            assertThat(calls).hasValue(2);
            assertThat(restarted.renewalStatus().state()).isEqualTo("stopped");
            assertThat(restarted.savedCertificate().orElseThrow().certificateChain().getFirst()).isEqualTo(initial);
        }
    }

    @Test
    void retainsTheActiveVersionWhenConfigurationChangesAfterMaterialPublication() throws Exception {
        AtomicReference<Runnable> afterWrite = new AtomicReference<>();
        InMemoryBlobStorage source = new InMemoryBlobStorage() {
            @Override
            public synchronized String write(BlobStorage.Modification modification) throws IOException {
                byte[] bytes = modification.bytes();
                String expected = modification.expectedRevision();
                String saved = super.write(new BlobStorage.Modification(expected, bytes));
                Runnable action = afterWrite.getAndSet(null);
                if (action != null) action.run();
                return saved;
            }
        };
        TestConfiguration desired = desiredState(false);
        try (OrionKeyMaterial owner = owner(source)) {
            AcmeCertificateService service = new AcmeCertificateService(desired.editor(), owner,
                    new RecordingIssuer(false), null, events.executor());
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            OrionDocument document = desired.editor().current().result().valueOrFailure("configuration");
            OrionHttpsConfiguration previous = document.system().https().orElseThrow();
            KeyMaterialId selected = previous.identity().orElseThrow();
            afterWrite.set(() -> {
                OrionHttpsConfiguration changed = new OrionHttpsConfiguration(previous.enabled(), "other-host",
                        previous.port(), previous.publicUrl(), previous.identity(),
                                previous.serverIssuerTrustAnchor(),
                        previous.clientAuthentication(), previous.clientTrustAnchors(), previous.acme());
                OrionDocument.SystemConfiguration system = document.system();
                desired.publish(new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(),
                        Optional.of(changed), system.secrets(), system.proxies(), system.connections(),
                        system.oidcProviders(), system.invitations()), document.organizations()),
                        Optional.of("intervening"));
            });
            assertThatThrownBy(() -> issue(service, AcmeCertificateService.IssueRequest.EMPTY))
                    .isInstanceOf(AcmeCertificateIssueException.class)
                    .hasMessage("Cannot activate issued ACME certificate");
            assertThat(desired.editor().current().result().valueOrFailure("configuration").system().https()
                    .orElseThrow().identity()).contains(selected);
            assertThat(service.savedCertificate().orElseThrow().certificateChain()).containsExactly(initial);
            assertThat(owner.read(selected).certificateChain()).containsExactly(initial);
            assertThat(owner.inventory()).contains(new KeyMaterialId(selected.alias(), selected.version() + 1));
        }
    }

    @Test
    void discardsCertificateIfSettingsChangeDuringIssuance() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage());
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            TestConfiguration desired = desiredState(false);
            OrionDocument initial = desired.editor().current().result()
                    .valueOrFailure("Cannot read configuration document");
            BlockingFirstIssuer issuer = new BlockingFirstIssuer();
            AcmeCertificateService service =
                    new AcmeCertificateService(desired.editor(), owner, issuer, null, events.executor());
            Future<IssuedAcmeCertificate> issuance = executor.submit(
                    () -> issue(service, AcmeCertificateService.IssueRequest.EMPTY));
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
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            issuer.before = Instant.now().minus(Duration.ofDays(90));
            issuer.after = Instant.now().plus(Duration.ofDays(90));
            AcmeCertificateService service = new AcmeCertificateService(
                    desiredState(false).editor(), owner, issuer, null, events.executor());
            X509Certificate initial = issue(service, AcmeCertificateService.IssueRequest.EMPTY)
                    .certificateChain().getFirst();
            Instant due = initial.getNotAfter().toInstant().minus(Duration.ofDays(30));
            assertThat(service.renewalStatus().nextAttempt()).isEqualTo(due.toString());
            maintain(service, due.minusSeconds(1));
            assertThat(issuer.calls).isEqualTo(1);
            maintain(service, due);
            assertThat(issuer.calls).isEqualTo(2);
        }
    }

    @Test
    void renewalLifecycleRunsEvenWhenHttpIsDisabled() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            BootstrapConfiguration bootstrap = bootstrap();
            bootstrap.getTransport().getHttp().setEnabled(false);
            TestConfiguration desired = desiredState(false);
            AcmeCertificateService service = new AcmeCertificateService(desired.editor(), owner,
                    new RecordingIssuer(false), null, events.executor());
            issue(service, AcmeCertificateService.IssueRequest.EMPTY);
            JettyHTTPServer server = new JettyHTTPServer(bootstrap, desired.editor(), owner, null, null, null);
            JettyHTTPServerStateMachine http = new JettyHTTPServerStateMachine(() -> server,
                    () -> { throw new AssertionError("Disabled HTTP must not start Git cleanup"); },
                    events.events(), events.executor());
            AcmeCertificateServiceStateMachine acme = new AcmeCertificateServiceStateMachine(() -> service);
            assertThat(http.start().failed()).isFalse();
            try {
                assertThat(acme.start().failed()).isFalse();
                assertThat(server.isRunning()).isFalse();
                assertThat(OrionUtils.waitForCondition(
                        () -> service.renewalStatus().state().equals("scheduled"))).isTrue();
                assertThat(http.stop().failed()).isFalse();
                assertThat(service.isRunning()).isTrue();
            } finally {
                assertThat(acme.stop().failed()).isFalse();
            }
            assertThat(service.renewalStatus().state()).isEqualTo("stopped");
            assertThat(events.executor().submit(() -> "still running").get(5, TimeUnit.SECONDS))
                    .isEqualTo("still running");
        }
    }

    @Test
    void schedulesRenewalChecksAndCancelsOnlyItsOwnFuture() throws Exception {
        try (RecordingScheduler scheduler = new RecordingScheduler();
                OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            RecordingIssuer issuer = new RecordingIssuer(false);
            issuer.before = Instant.now().minus(Duration.ofDays(80));
            issuer.after = Instant.now().plus(Duration.ofDays(10));
            TestConfiguration desired = desiredState(false);
            AcmeCertificateService initial = new AcmeCertificateService(
                    desired.editor(), owner, issuer, null, scheduler);
            initial.issue(AcmeCertificateService.IssueRequest.EMPTY);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, issuer, null, scheduler);
            service.onStart();
            try {
                assertThat(scheduler.initialDelay).isZero();
                assertThat(scheduler.delay).isEqualTo(60);
                assertThat(scheduler.unit).isEqualTo(TimeUnit.SECONDS);
                assertThat(issuer.calls).isEqualTo(1);
                scheduler.command.run();
                assertThat(issuer.calls).isEqualTo(2);
            } finally {
                service.onStop();
            }
            assertThat(scheduler.future.isCancelled()).isTrue();
            assertThat(scheduler.submit(() -> "still running").get(5, TimeUnit.SECONDS))
                    .isEqualTo("still running");
        }
    }

    private static final class RecordingScheduler extends OrionExecutor {
        private Runnable command;
        private long initialDelay;
        private long delay;
        private TimeUnit unit;
        private ScheduledFuture<?> future;

        private RecordingScheduler() {
            super(2, new OrionThreadFactory());
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            this.command = command;
            this.initialDelay = initialDelay;
            this.delay = delay;
            this.unit = unit;
            future = super.scheduleWithFixedDelay(command, 1, 1, TimeUnit.DAYS);
            return future;
        }
    }

    private BootstrapConfiguration bootstrap() {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        return configuration;
    }

    private void saveSettings(AcmeCertificateService service) {
        AcmeCertificateService.View settings = service.view();
        service.save(new AcmeCertificateService.Settings(settings.revision(), settings.provider(),
                settings.directoryUrl(), settings.accountEmail(), settings.domains(), "", null, null), "root");
        events.awaitEvents();
    }

    private TestConfiguration desiredState(boolean allowRequestedDomains) {
        return desiredState(allowRequestedDomains, Optional.empty());
    }

    private TestConfiguration desiredState(boolean allowRequestedDomains,
            Optional<KeyMaterialId> issuer) {
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true,
                URI.create("acme://letsencrypt.org/staging"),
                "admin@example.test",
                List.of("example.test"),
                "ORION",
                Optional.of(new KeyMaterialId("acme-account-v1", 1)),
                30,
                40,
                true,
                allowRequestedDomains, Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                false,
                "localhost",
                8443,
                URI.create("https://example.test"),
                Optional.of(new KeyMaterialId("https-identity-v1", 1)),
                issuer,
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

    private IssuedAcmeCertificate issue(AcmeCertificateService service,
            AcmeCertificateService.IssueRequest request) {
        IssuedAcmeCertificate certificate = service.issue(request);
        events.awaitEvents();
        return certificate;
    }

    private void maintain(AcmeCertificateService service, Instant now) {
        service.maintainCertificate(now);
        events.awaitEvents();
    }

    private static OrionKeyMaterial owner(InMemoryBlobStorage store) throws Exception {
        return owner(store, new OrionEventManager());
    }

    private static OrionKeyMaterial owner(InMemoryBlobStorage store,
            OrionEventManager eventManager) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    eventManager));
        }
    }

    private static KeyMaterialDescriptor descriptor(String alias, KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(
                alias,
                Set.of(purpose),
                KeyMaterialAlgorithm.RSA,
                1);
    }

    private static final class RecordingIssuer extends AcmeCertificateIssuer {
        private final boolean wrongKey;
        private AcmeCertificateIssueRequest lastRequest;
        private RuntimeException failure;
        private TestCertificateChain.Authority root;
        private boolean omitRoot;
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
                X509Certificate leaf;
                if (root != null) leaf = TestCertificateChain.leaf("example.test", keyPair, root);
                else if (before == null) leaf = TestCertificateChain.selfSignedLeaf("example.test", keyPair);
                else leaf = TestCertificateChain.selfSignedLeaf("example.test", keyPair, before, after);
                List<X509Certificate> chain =
                        root == null || omitRoot ? List.of(leaf) : List.of(leaf, root.certificate());
                return new IssuedAcmeCertificate(request.domains(), chain);
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
