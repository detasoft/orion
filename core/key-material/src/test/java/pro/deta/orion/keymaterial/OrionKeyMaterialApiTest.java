package pro.deta.orion.keymaterial;

import pro.deta.orion.util.VcResourceModified;

import pro.deta.orion.keymaterial.KeyMaterialTestRuntime.TestBlobStorage;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.read;
import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.write;
import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.revision;


import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.BlobStorageConflictException;
import pro.deta.orion.storage.InMemoryBlobStorage;

import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.event.OrionEventManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionKeyMaterialApiTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final KeyMaterialTestRuntime runtime = new KeyMaterialTestRuntime();


    private final KeyMaterialId SIGNING =
            new KeyMaterialId("signing", 1);
    private final KeyMaterialId OTHER = new KeyMaterialId("other", 1);
    private final KeyMaterialId IDENTITY = new KeyMaterialId("https", 1);
    private final KeyMaterialId ROOT = new KeyMaterialId("root", 1);

    @Test
    void releasesStateMonitorDuringStoreWriteAndChangePublication() throws Exception {
        AtomicReference<OrionKeyMaterial> materialRef =
                new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            InMemoryBlobStorage store = new TestBlobStorage(runtime.events) {
                @Override
                protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                    assertReadable(executor, materialRef.get());
                    return super.commitSnapshot(operation, description);
                }
            };
            try (OrionKeyMaterial material = open(store, new OrionEventManager() {
                @Override
                public boolean isRunning() { return true; }
                @Override
                public void publish(OrionEvent event) {
                    assertReadable(executor, materialRef.get());
                }
            })) {
                materialRef.set(material);
                try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                    modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                assertThat(material.revision()).isEqualTo("2");
                assertThat(material.contains(SIGNING)).isTrue();
            }
        }
    }

    @Test
    void releasesStateMonitorBeforePublishingReloadChanges() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        AtomicReference<OrionKeyMaterial> readerRef =
                new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                OrionKeyMaterial reader = open(store, new OrionEventManager() {
                @Override
                public boolean isRunning() { return true; }
                    @Override
                    public void publish(OrionEvent event) {
                        assertReadable(executor, readerRef.get());
                    }
                });
                OrionKeyMaterial writer = open(store)) {
            readerRef.set(reader);
            try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            reader.reload(revision(store));
            assertThat(reader.contains(SIGNING)).isTrue();
        }
    }

    @Test
    void acceptsItsOwnReloadWhileStoreWriteIsCompleting() throws Exception {
        AtomicReference<OrionKeyMaterial> materialRef =
                new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events) {
            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                VcRevision.Stored saved = super.commitSnapshot(operation, description);
                try {
                    materialRef.get().reload(saved.value());
                } catch (GeneralSecurityException failure) {
                    throw new IOException(failure);
                }
                return saved;
            }
        };
        OrionEventManager events = runtime.events;
        events.forTarget(this).subscribe(KeyMaterialChanged.class, null, event -> changes.incrementAndGet());
        events.onStart();
        try (OrionKeyMaterial material = open(store, events)) {
            materialRef.set(material);
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(material.revision()).isEqualTo("2");
            assertThat(material.contains(SIGNING)).isTrue();
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).hasValue(1);
        } finally {
            events.onStop();
        }
    }

    private static void assertReadable(ExecutorService executor, OrionKeyMaterial material) {
        try {
            executor.submit(material::revision).get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("KMS state monitor is held during publication or persistence", failure);
        }
    }

    @Test
    void rejectsOpeningMaterialWithoutAnEventManager() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            assertThatThrownBy(() -> OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    null)))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void savesMaterialWithAnUnstartedRunningAndStoppedEventManager() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        OrionEventManager events = runtime.events;
        List<Set<String>> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        events.forTarget(this).subscribe(KeyMaterialChanged.class, null, event -> changes.add(event.resources()));
        try (OrionKeyMaterial material = open(store, events)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(changes).isEmpty();
            events.onStart();
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).containsExactly(Set.of(OTHER.alias()));
            events.onStop();
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.delete(OTHER);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(changes).containsExactly(Set.of(OTHER.alias()));
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(material.contains(OTHER)).isFalse();
            assertThat(material.revision()).isEqualTo("4");
        } finally {
            if (events.isRunning()) events.onStop();
        }
    }

    @Test
    void inventoriesKeysAndTrustAnchorsByExactIdAfterReopening() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId cipher = new KeyMaterialId("cipher", 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Inventory root");
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey("cipher", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                        KeyMaterialAlgorithm.RSA, root.certificate());
                modification.commit(VcChangeDescription.EMPTY);
            }
        }
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.inventory()).containsExactlyInAnyOrder(SIGNING, cipher, ROOT);
            assertThat(material.contains(ROOT)).isTrue();
            assertThat(material.read(ROOT).trustedCertificate()).isEqualTo(root.certificate());
            assertThat(material.read(SIGNING).keyPair().getPrivate()).isNotNull();
            assertThat(material.read(cipher).content()).isInstanceOf(KeyStore.SecretKeyEntry.class);
        }
    }

    @Test
    void enforcesEntryKindsAtOperationsUsingTheCommonDescriptor() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        TestCertificateChain.Authority root = TestCertificateChain.root("Operation root");
        KeyMaterialId trustedIdentity = new KeyMaterialId("root", 1);
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                assertThatThrownBy(() -> modification.generateKey("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, 2048))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Trust anchors");
                assertThatThrownBy(() -> modification.importKey("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root.keyPair()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Trust anchors");
                assertThatThrownBy(() -> modification.setTrustedCertificate(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, root.certificate()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRUST_ANCHOR");
                modification.generateKey(("https"), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA, 2048);
                assertThatThrownBy(() -> modification.installCertificateChain(IDENTITY,
                        List.of(root.certificate()), Optional.of(IDENTITY)))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRUST_ANCHOR");
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                        KeyMaterialAlgorithm.RSA, root.certificate());
                assertThatThrownBy(() -> modification.installCertificateChain(trustedIdentity,
                        List.of(root.certificate()), Optional.empty()))
                        .isInstanceOf(IllegalArgumentException.class);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThatThrownBy(() -> material.read(ROOT)
                    .keyPair()).isInstanceOf(GeneralSecurityException.class);
            assertThatThrownBy(() -> material.read(IDENTITY).trustedCertificate())
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("Trusted certificate");
            assertThatThrownBy(() -> material.certificateIdentity(new TlsMaterialConfiguration(trustedIdentity, Optional.empty(),
                    List.of(), TlsClientAuthentication.DISABLED))).isInstanceOf(GeneralSecurityException.class);
            assertThatThrownBy(() -> material.certificateIdentity(new TlsMaterialConfiguration(IDENTITY, Optional.of(IDENTITY),
                    List.of(), TlsClientAuthentication.DISABLED))).isInstanceOf(GeneralSecurityException.class);
            assertThatThrownBy(() -> material.certificateIdentity(new TlsMaterialConfiguration(IDENTITY, Optional.empty(),
                    List.of(IDENTITY), TlsClientAuthentication.REQUIRED))).isInstanceOf(GeneralSecurityException.class);
        }
    }

    @Test
    void persistsPurposeSetsAndSearchesForAllRequestedFlagsAfterReload() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        Set<KeyMaterialPurpose> signingAndHost =
                Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST);
        Set<KeyMaterialPurpose> trustAndCa =
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        KeyMaterialId shared = new KeyMaterialId("shared", 1);
        KeyMaterialId trusted = new KeyMaterialId("shared-root", 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Shared root");
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey("shared", signingAndHost, KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate("shared-root", trustAndCa, KeyMaterialAlgorithm.RSA, root.certificate());
                modification.commit(VcChangeDescription.EMPTY);
            }
        }
        try (OrionKeyMaterial material = open(store)) {

            assertThat(material.read(trusted).purposes()).containsAll(trustAndCa);
            assertThat(material.read(shared).purposes()).containsAll(signingAndHost);
            assertThat(material.contains("shared", signingAndHost)).isTrue();
            assertThat(material.contains("shared", trustAndCa)).isFalse();
            assertThat(material.contains("missing", signingAndHost)).isFalse();
            KeyMaterialId signing = new KeyMaterialId("shared", 1);
            byte[] payload = "multi-purpose key".getBytes(StandardCharsets.UTF_8);
            assertThat(material.verify(signing, payload, material.sign(signing, payload))).isTrue();
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.delete(signing);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(material.contains("shared", Set.of())).isFalse();

            assertThat(material.inventory()).doesNotContain(shared).hasSize(2);
        }
    }

    @Test
    void certificateUpdatesThroughSubsetDescriptorsPreserveEveryStoredPurpose() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        Set<KeyMaterialPurpose> identityPurposes =
                Set.of(KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialPurpose.SERVER_SIGNING);
        Set<KeyMaterialPurpose> rootPurposes =
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        KeyMaterialId fullIdentity = new KeyMaterialId(IDENTITY.alias(), IDENTITY.version());
        KeyMaterialId fullRoot = new KeyMaterialId(ROOT.alias(), ROOT.version());
        TestCertificateChain.Authority root = TestCertificateChain.root("Root");
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(IDENTITY.alias(), identityPurposes, KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate(ROOT.alias(), rootPurposes, KeyMaterialAlgorithm.RSA, root.certificate());
                modification.commit(VcChangeDescription.EMPTY);
            }
            X509Certificate leaf =
                    TestCertificateChain.leaf("example.test", material.read(IDENTITY).keyPair(), root);
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.setTrustedCertificate(ROOT, root.certificate());
                modification.installCertificateChain(IDENTITY, List.of(leaf), Optional.of(ROOT));
                modification.commit(VcChangeDescription.EMPTY);
            }
        }
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.read(new KeyMaterialId(fullIdentity.alias(), 2)).purposes())
                    .isEqualTo(identityPurposes);
            assertThat(material.read(new KeyMaterialId(fullRoot.alias(),
                    2)).purposes()).isEqualTo(rootPurposes);
            assertThat(material.read(fullIdentity).certificateChain()).hasSize(1);
            assertThat(material.read(fullRoot).trustedCertificate()).isEqualTo(root.certificate());
            assertThat(material.createTlsContext(new TlsMaterialConfiguration(
                    material.read(new KeyMaterialId(IDENTITY.alias(), 2)).id(), Optional.of(ROOT),
                    List.of(fullRoot), TlsClientAuthentication.WANT)).getProtocol()).isEqualTo("TLS");
        }
    }

    @Test
    void publishesGeneratedKeysTogetherAndSignsAfterReload() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId host = new KeyMaterialId("ssh", 1);
        byte[] payload = "token".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            modification.generateKey(("ssh"), Set.of((KeyMaterialPurpose.SSH_HOST)), KeyMaterialAlgorithm.RSA, 2048);

            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.revision()).isEqualTo("1");
            assertThat(modification.revision().value()).isEqualTo("1");
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.inventory()).isEmpty();
            assertThat(read(store)).isNotEmpty();

            modification.commit(VcChangeDescription.EMPTY);

            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.revision()).isEqualTo("2");
            assertThat(revision(store)).isEqualTo("2");

            assertThat(material.sshHostKeyPairs(List.of())).hasSize(1);
            signature = material.sign(SIGNING, payload);
            publicKey = material.read(SIGNING).publicKey().getEncoded();
            modification.close();
            assertThatThrownBy(() -> modification.commit(VcChangeDescription.EMPTY)).isInstanceOf(IllegalStateException.class);
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.hasDurableSnapshot()).isTrue();
            assertThat(reloaded.revision()).isEqualTo("2");
            assertThat(reloaded.read(SIGNING).publicKey().getEncoded()).isEqualTo(publicKey);
            assertThat(reloaded.verify(SIGNING, payload, signature)).isTrue();
            assertThat(reloaded.verify(SIGNING, new byte[]{1}, signature)).isFalse();
        }
    }

    @Test
    void reportsChangedAliasesOnlyAfterACommittedModification() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        List<KeyMaterialChanged> changes = new ArrayList<>();
        OrionEventManager events = runtime.events;
        events.forTarget(this).subscribe(KeyMaterialChanged.class, null, changes::add);
        events.onStart();
        try (OrionKeyMaterial material = open(store, events)) {
            KeyMaterialOperation discarded = material.open(new VcRevision.Stored(material.revision()));
            discarded.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            discarded.close();
            material.open(new VcRevision.Stored(material.revision())).commit(VcChangeDescription.EMPTY);
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).isEmpty();
            X509Certificate root = TestCertificateChain.root("Root").certificate();

            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root);
                modification.commit(VcChangeDescription.EMPTY);
            }

            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).extracting(KeyMaterialChanged::resources)
                    .containsExactly(Set.of(SIGNING.alias(), ROOT.alias()));
        } finally {
            events.onStop();
        }
    }

    @Test
    void importsSecretKeyAndAuthenticatesEnvelopesAfterReload() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        ConfigurationSecretContext context = new ConfigurationSecretContext("github-token", "access-token");
        byte[] plaintext = "password".getBytes(StandardCharsets.UTF_8);
        KeyMaterialId cipher;
        ConfigurationSecretEnvelope envelope;
        try (OrionKeyMaterial material = open(store)) {
            cipher = material.configurationCipherKey();
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            modification.importSecretKey(
                    cipher.alias(),
                    Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER),
                    KeyMaterialAlgorithm.AES,
                    generator.generateKey());
            modification.commit(VcChangeDescription.EMPTY);
            envelope = material.seal(cipher, plaintext, context);
            assertThat(material.revision()).isEqualTo("2");
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.open(cipher, envelope, context)).isEqualTo(plaintext);
            assertThatThrownBy(() -> reloaded.open(cipher, envelope,
                    new ConfigurationSecretContext("other-token", "access-token")))
                    .isInstanceOfSatisfying(ConfigurationSecretException.class, failure ->
                            assertThat(failure.reason())
                                    .isEqualTo(ConfigurationSecretException.Reason.AUTHENTICATION_FAILED));
        }
    }

    @Test
    void importsPemAndClearsBothAcceptedAndRejectedInput() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyPair imported = TestCertificateChain.keyPair();
        char[] pem = ("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(imported.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n").toCharArray();
        char[] rejected = "invalid private key".toCharArray();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            assertThatThrownBy(() -> modification.importKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, rejected))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(rejected).containsOnly('\0');
            modification.importKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, pem);
            assertThat(pem).containsOnly('\0');
            modification.commit(VcChangeDescription.EMPTY);
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.read(SIGNING).publicKey().getEncoded())
                    .isEqualTo(imported.getPublic().getEncoded());
            assertThat(reloaded.contains(OTHER)).isFalse();
        }
    }

    @Test
    void installsPendingIdentityAndIssuerTogetherWithoutPublishingInvalidChain() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        KeyPair keyPair = TestCertificateChain.keyPair();
        X509Certificate leaf = TestCertificateChain.leaf("orion.example", keyPair, intermediate);
        X509Certificate wrongLeaf = TestCertificateChain.leaf(
                "other.example", TestCertificateChain.keyPair(), intermediate);
        List<X509Certificate> chain = List.of(leaf, intermediate.certificate());
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            modification.importKey(("https"), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA, keyPair);
            modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root.certificate());
            assertThatThrownBy(() -> modification.installCertificateChain(
                    IDENTITY, List.of(wrongLeaf, intermediate.certificate()), Optional.of(ROOT)))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("public key");
            modification.installCertificateChain(IDENTITY, chain, Optional.of(ROOT));
            assertThat(material.contains(IDENTITY)).isFalse();
            assertThat(material.contains(ROOT)).isFalse();
            modification.commit(VcChangeDescription.EMPTY);
            assertThat(material.revision()).isEqualTo("2");
            assertThat(material.read(new KeyMaterialId(IDENTITY.alias(),
                    2)).issuedCertificateChain().orElseThrow())
                    .containsExactlyElementsOf(chain);
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.read(new KeyMaterialId(IDENTITY.alias(),
                    2)).certificateChain()).containsExactlyElementsOf(chain);
            assertThat(reloaded.read(ROOT).trustedCertificate()).isEqualTo(root.certificate());
            TlsMaterialConfiguration tls = new TlsMaterialConfiguration(
                    reloaded.read(new KeyMaterialId(IDENTITY.alias(), 2)).id(),
                    Optional.of(ROOT), List.of(), TlsClientAuthentication.DISABLED);
            assertThat(reloaded.createTlsContext(tls).getSocketFactory().getSupportedCipherSuites())
                    .isNotEmpty();
        }
    }

    @Test
    void discardsChangesAndKeepsTheOwnerUsable() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialOperation discarded = material.open(new VcRevision.Stored(material.revision()));
            discarded.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            discarded.close();
            discarded.close();

            assertThatThrownBy(() -> discarded.commit(VcChangeDescription.EMPTY)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> discarded.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.revision()).isEqualTo("1");
            assertThat(read(store)).isNotEmpty();

            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(material.contains(OTHER)).isTrue();
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.revision()).isEqualTo("2");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeDiscardsChangesEvenWhenPreparationThrows(boolean fail) throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        byte[] before = read(store);
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialOperation operation = material.open(new VcRevision.Latest());
            org.assertj.core.api.ThrowableAssert.ThrowingCallable prepare = () -> {
                try (operation) {
                    operation.generateKey("signing", Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                            KeyMaterialAlgorithm.RSA, 2048);
                    if (fail) throw new IOException("preparation failed");
                }
            };
            if (fail) {
                assertThatThrownBy(prepare).isInstanceOf(IOException.class).hasMessage("preparation failed");
            } else {
                org.assertj.core.api.Assertions.assertThatCode(prepare).doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> operation.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.revision()).isEqualTo("1");
            assertThat(read(store)).isEqualTo(before);
        }
    }

    @Test
    void emptyAndRepeatedGenerationDoNotPublishNewVersions() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(store)) {
            material.open(new VcRevision.Stored(material.revision())).commit(VcChangeDescription.EMPTY);
            assertThat(read(store)).isNotEmpty();
            assertThat(material.revision()).isEqualTo("1");
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            String beforeRevision = revision(store);
            byte[] beforeBytes = read(store, beforeRevision);
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(material.revision()).isEqualTo("2");
            assertThat(read(store)).isEqualTo(beforeBytes);
            assertThat(revision(store)).isEqualTo(beforeRevision);
        }
    }

    @Test
    void onlyOneConcurrentModificationCanApplyEvenForDifferentAliases() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialOperation first = material.open(new VcRevision.Stored(material.revision()));
            KeyMaterialOperation second = material.open(new VcRevision.Stored(material.revision()));
            first.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            second.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            CyclicBarrier start = new CyclicBarrier(2);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<Boolean> firstApplied = executor.submit(() -> applyTogether(first, start));
                Future<Boolean> secondApplied = executor.submit(() -> applyTogether(second, start));
                boolean firstWon = firstApplied.get(10, TimeUnit.SECONDS);
                boolean secondWon = secondApplied.get(10, TimeUnit.SECONDS);
                assertThat(firstWon).isNotEqualTo(secondWon);
                assertThat(material.contains(SIGNING)).isEqualTo(firstWon);
                assertThat(material.contains(OTHER)).isEqualTo(secondWon);
            }
            assertThat(material.revision()).isEqualTo("2");
            assertThat(revision(store)).isEqualTo("2");
            assertThat(material.inventory()).hasSize(1);
            assertThatThrownBy(() -> first.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(BlobStorageConflictException.class);
            assertThatThrownBy(() -> second.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(BlobStorageConflictException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.inventory()).hasSize(1);
            assertThat(reloaded.revision()).isEqualTo("2");
        }
    }

    @Test
    void rejectsAnOutdatedRevisionBeforeCallingTheModificationOperation() throws Exception {
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(source)) {
            String previous = material.revision();
            KeyMaterialOperation pending = material.open(new VcRevision.Stored(previous));
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(previous))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            try (KeyMaterialOperation historical = material.open(new VcRevision.Stored(previous))) {
                assertThat(historical.contains(SIGNING.alias())).isFalse();
            }
            assertThatThrownBy(() -> pending.commit(VcChangeDescription.EMPTY)).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(BlobStorageConflictException.class);
            assertThat(material.revision()).isEqualTo(revision(source)).isNotEqualTo(previous);
            assertThat(material.contains(SIGNING)).isTrue();
        }
    }

    @Test
    void usesTheSourceRevisionEvenWhenMaterialContentHasNotChanged() throws Exception {
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            byte[] bytes = codec.encode(codec.create());
            try {
                write(source, null, bytes);
                write(source, revision(source), bytes);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
        try (OrionKeyMaterial material = open(source)) {
            assertThat(revision(source)).isEqualTo("3");
            assertThat(material.revision()).isEqualTo("3");
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(material.revision()).isEqualTo("4");
            assertThat(revision(source)).isEqualTo("4");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handlesSourceEventsDuringSuccessfulAndFailedWrites(boolean responseLost) throws Exception {
        OrionEventManager events = runtime.events;
        AtomicInteger notifications = new AtomicInteger();
        events.forTarget(this).subscribe(KeyMaterialChanged.class, null, event -> notifications.incrementAndGet());
        events.onStart();
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events) {


            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                VcRevision.Stored saved = super.commitSnapshot(operation, description);
                runtime.events.publishAndWait(new VcResourceModified(this, operation.revision(), saved));
                if (responseLost) throw new IOException("Write response lost");
                return saved;
            }
        };
        try (OrionKeyMaterial material = open(source, events)) {
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            if (responseLost) {
                assertThatThrownBy(() -> modification.commit(VcChangeDescription.EMPTY))
                        .isInstanceOf(java.io.UncheckedIOException.class).hasRootCauseMessage("Write response lost");
                assertThatThrownBy(material::revision)
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            } else {
                modification.commit(VcChangeDescription.EMPTY);
                assertThat(material.revision()).isEqualTo("2");
            }
            events.publishAndWait(new OrionEvent() {});
            assertThat(notifications).hasValue(responseLost ? 0 : 1);
        } finally {
            events.onStop();
        }
        try (OrionKeyMaterial reopened = open(source)) {
            assertThat(reopened.contains(SIGNING)).isTrue();
            assertThat(reopened.revision()).isEqualTo("2");
        }
    }

    @Test
    void rejectsAnOutdatedOwnerWithoutOverwritingTheStoredWinner() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial first = open(store); OrionKeyMaterial second = open(store)) {
            KeyMaterialOperation winner = first.open(new VcRevision.Stored(first.revision()));
            KeyMaterialOperation loser = second.open(new VcRevision.Stored(second.revision()));
            winner.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            loser.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            winner.commit(VcChangeDescription.EMPTY);
            String beforeRevision = revision(store);
            byte[] beforeBytes = read(store, beforeRevision);

            assertThatThrownBy(() -> loser.commit(VcChangeDescription.EMPTY)).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(BlobStorageConflictException.class);
            assertThatThrownBy(() -> second.contains(OTHER)).isInstanceOf(IllegalStateException.class);
            assertThat(first.contains(SIGNING)).isTrue();
            assertThat(read(store)).isEqualTo(beforeBytes);
            assertThat(revision(store)).isEqualTo(beforeRevision);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.contains(SIGNING)).isTrue();
            assertThat(reloaded.contains(OTHER)).isFalse();
            assertThat(reloaded.revision()).isEqualTo("2");
        }
    }

    @Test
    void deletionAppendsVersionsAndKeepsOldCertificatesAfterReload() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        X509Certificate original = TestCertificateChain.root("Original root").certificate();
        X509Certificate replacement = TestCertificateChain.root("Replacement root").certificate();
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, original);
                modification.commit(VcChangeDescription.EMPTY);
            }
            publicKey = material.read(SIGNING).publicKey().getEncoded();
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.setTrustedCertificate(ROOT, replacement);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(material.read(new KeyMaterialId(ROOT.alias(),
                    2)).trustedCertificate()).isEqualTo(replacement);
            assertThat(material.revision()).isEqualTo("3");

            KeyMaterialOperation deletion = material.open(new VcRevision.Stored(material.revision()));
            deletion.delete(SIGNING);
            deletion.delete(ROOT);
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(material.read(new KeyMaterialId(ROOT.alias(),
                    2)).trustedCertificate()).isEqualTo(replacement);
            deletion.commit(VcChangeDescription.EMPTY);

            assertThat(material.revision()).isEqualTo("4");
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.contains(ROOT)).isFalse();
            assertThat(material.inventory()).isEmpty();

            assertThatThrownBy(() -> material.sign(SIGNING, new byte[]{1}))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.revision()).isEqualTo("4");
            assertThat(reloaded.contains(SIGNING)).isFalse();
            assertThat(reloaded.contains(ROOT)).isFalse();
            assertThat(reloaded.inventory()).isEmpty();
            assertThatThrownBy(() -> reloaded.read(ROOT).trustedCertificate())
                    .isInstanceOf(GeneralSecurityException.class);
        }

        KeyStore history = KeyStore.getInstance("PKCS12");
        history.load(new ByteArrayInputStream(read(store)),
                KeyMaterialTestConstants.password());
        assertThat(history.size()).isEqualTo(5);
        assertThat(history.getCertificate("signing").getPublicKey().getEncoded()).isEqualTo(publicKey);
        assertThat(history.getCertificate("root")).isEqualTo(original);
        List<Certificate> certificates = new ArrayList<>();
        for (String alias : Collections.list(history.aliases())) {
            certificates.add(history.getCertificate(alias));
        }
        assertThat(certificates).contains(original, replacement);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSaveClosesTheOwnerAndReopensOnlyDurableChanges(boolean committed) throws Exception {
        FailingStore store = new FailingStore(committed);
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            modification.delete(SIGNING);
            store.fail = true;

            assertThatThrownBy(() -> modification.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasRootCauseMessage("save failed");
            assertThatThrownBy(() -> material.contains(SIGNING)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> modification.commit(VcChangeDescription.EMPTY)).isInstanceOf(IllegalStateException.class);
        }
        store.fail = false;
        String durableRevision = revision(store);
        byte[] durableBytes = read(store, durableRevision);
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.revision()).isEqualTo(committed ? "3" : "2");
            assertThat(reloaded.contains(SIGNING)).isEqualTo(!committed);
            assertThat(reloaded.contains(OTHER)).isEqualTo(committed);
            KeyMaterialId available = committed ? OTHER : SIGNING;
            byte[] payload = new byte[]{1};
            assertThat(reloaded.verify(available, payload, reloaded.sign(available, payload))).isTrue();
            assertThat(read(store)).isEqualTo(durableBytes);
            assertThat(revision(store)).isEqualTo(durableRevision);
        }
    }

    @Test
    void rejectsMetadataMismatchWithoutChangingExistingKeys() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            String beforeRevision = revision(store);
            byte[] beforeBytes = read(store, beforeRevision);
            KeyMaterialId otherPurpose = new KeyMaterialId("signing", 1);
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            assertThatThrownBy(() -> modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA, 2048))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            assertThatThrownBy(() -> material.require(otherPurpose,
                    Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            modification.commit(VcChangeDescription.EMPTY);

            assertThat(material.revision()).isEqualTo("2");
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(read(store)).isEqualTo(beforeBytes);
            assertThat(revision(store)).isEqualTo(beforeRevision);
        }
    }

    @Test
    void reloadsTheRequestedRevisionAndInvalidatesPreparedModifications() throws Exception {
        TestBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId secret = new KeyMaterialId("wrapping", 1);
        X509Certificate root = TestCertificateChain.root("Reload root").certificate();
        List<KeyMaterialChanged> changes = new ArrayList<>();
        OrionEventManager events = new OrionEventManager();
        events.forTarget(this).subscribe(KeyMaterialChanged.class, null, changes::add);
        events.onStart();
        try (OrionKeyMaterial reader = open(store, events);
             OrionKeyMaterial writer = open(store)) {
            try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey("wrapping", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root);
                modification.commit(VcChangeDescription.EMPTY);
            }
            reader.reload(revision(store));
            KeyMaterialOperation stale = reader.open(new VcRevision.Stored(reader.revision()));
            stale.generateKey(("stale"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            try {
                try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                    modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                String added = revision(store);
                try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                    modification.delete(OTHER);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                String deleted = revision(store);
                reader.reload(added);
                assertThat(reader.contains(OTHER)).isTrue();
                assertThat(reader.revision()).isEqualTo("3");
                assertThatThrownBy(() -> stale.commit(VcChangeDescription.EMPTY)).isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(BlobStorageConflictException.class);
                reader.reload(deleted);
                assertThat(reader.contains(OTHER)).isFalse();
                assertThat(reader.contains(SIGNING)).isTrue();
                assertThat(reader.contains(secret)).isTrue();
                assertThat(reader.contains(ROOT)).isTrue();
                assertThat(reader.revision()).isEqualTo("4");
                reader.reload(deleted);
                events.publishAndWait(new OrionEvent() {});
                assertThat(changes).extracting(KeyMaterialChanged::resources)
                        .containsExactly(Set.of("signing", "wrapping", "root"), Set.of("other"), Set.of("other"));
                assertThatThrownBy(() -> reader.reload(added)).isInstanceOf(GeneralSecurityException.class);
                assertThat(reader.revision()).isEqualTo("4");
                try (KeyMaterialOperation modification = reader.open(new VcRevision.Stored(reader.revision()))) {
                    modification.generateKey(
                            ("after-reload"),
                            Set.of((KeyMaterialPurpose.SERVER_SIGNING)),
                            KeyMaterialAlgorithm.RSA,
                            2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                assertThat(reader.revision()).isEqualTo("5");
            } finally {
                stale.close();
            }
        } finally {
            events.onStop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsWorkingKeysWhenAnExternalRevisionIsCorruptOrReplacesHistory(boolean corrupt) throws Exception {
        TestBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial reader = open(store)) {
            try (KeyMaterialOperation modification = reader.open(new VcRevision.Stored(reader.revision()))) {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            byte[] payload = new byte[]{1, 2, 3};
            byte[] signature = reader.sign(SIGNING, payload);
            byte[] replacement;
            InMemoryBlobStorage otherStore = new TestBlobStorage(runtime.events);
            try (OrionKeyMaterial other = open(otherStore)) {
                try (KeyMaterialOperation modification = other.open(new VcRevision.Stored(other.revision()))) {
                    modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                replacement = read(otherStore);
            }
            String revision = write(store, revision(store),
                    corrupt ? new byte[]{1, 2, 3} : replacement);
            assertThatThrownBy(() -> reader.reload(revision)).isInstanceOfAny(
                    IOException.class, GeneralSecurityException.class);
            assertThat(reader.revision()).isEqualTo("2");
            assertThat(reader.verify(SIGNING, payload, signature)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesItsStoreOnCloseOrWhenLoadingFails(boolean invalidMaterial) throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events) {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        if (invalidMaterial) write(store, null, new byte[]{1, 2, 3});
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            if (invalidMaterial) {
                assertThatThrownBy(() -> OrionKeyMaterial.open(new KeyMaterialRepository(
                        store,
                        new KeyMaterialCodec(options),
                        runtime.events)))
                        .isInstanceOfAny(IOException.class, GeneralSecurityException.class);
            } else {
                OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                        store,
                        new KeyMaterialCodec(options),
                        runtime.events));
                material.close();
                material.close();
            }
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void remainsClosedWhenStoreCleanupFails() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events) {
            @Override
            public void close() {
                closes.incrementAndGet();
                throw new IllegalStateException("Store cleanup failed");
            }
        };
        OrionKeyMaterial material = open(store);

        assertThatThrownBy(material::close)
                .isInstanceOf(IllegalStateException.class).hasMessage("Store cleanup failed");
        assertThatThrownBy(material::revision)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> material.open(new VcRevision.Stored(material.revision())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        material.close();
        assertThat(closes).hasValue(1);
    }



    private static boolean applyTogether(KeyMaterialOperation modification, CyclicBarrier start)
            throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            modification.commit(VcChangeDescription.EMPTY);
            return true;
        } catch (java.io.UncheckedIOException failure) {
            if (!(failure.getCause() instanceof BlobStorageConflictException)) throw failure;
            return false;
        }
    }



    private OrionKeyMaterial open(BlobStorage store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    runtime.events));
        }
    }

    private static OrionKeyMaterial open(BlobStorage store, OrionEventManager events)
            throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    events));
        }
    }

    private final class FailingStore extends TestBlobStorage {
        private final boolean committed;
        private boolean fail;

        private FailingStore(boolean committed) {
            super(runtime.events);
            this.committed = committed;
        }

        @Override
        protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
            if (fail) {
                if (committed) super.commitSnapshot(operation, description);
                throw new IOException("save failed");
            }
            return super.commitSnapshot(operation, description);
        }
    }
}
