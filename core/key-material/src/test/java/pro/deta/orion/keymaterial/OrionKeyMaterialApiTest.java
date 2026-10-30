package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.BlobChanged;
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
import java.util.Map;
import java.util.HashMap;
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

    private static final KeyMaterialId SIGNING =
            new KeyMaterialId("signing", 1);
    private static final KeyMaterialId OTHER = new KeyMaterialId("other", 1);
    private static final KeyMaterialId IDENTITY = new KeyMaterialId("https", 1);
    private static final KeyMaterialId ROOT = new KeyMaterialId("root", 1);

    @Test
    void releasesStateMonitorDuringStoreWriteAndChangePublication() throws Exception {
        AtomicReference<OrionKeyMaterial> materialRef =
                new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            InMemoryBlobStorage store = new InMemoryBlobStorage() {
                @Override
                public synchronized String write(BlobStorage.Modification modification) throws IOException {
                    byte[] bytes = modification.bytes();
                    String expectedRevision = modification.expectedRevision();
                    assertReadable(executor, materialRef.get());
                    return super.write(new BlobStorage.Modification(expectedRevision, bytes));
                }
            };
            try (OrionKeyMaterial material = open(store, new OrionEventManager() {
                @Override
                public void publishIfRunning(OrionEvent event) {
                    assertReadable(executor, materialRef.get());
                }
            })) {
                materialRef.set(material);
                material.withModification(material.revision(), modification -> {
                    modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.apply();
                });
                assertThat(material.revision()).isEqualTo("1");
                assertThat(material.contains(SIGNING)).isTrue();
            }
        }
    }

    @Test
    void releasesStateMonitorBeforePublishingReloadChanges() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        AtomicReference<OrionKeyMaterial> readerRef =
                new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                OrionKeyMaterial reader = open(store, new OrionEventManager() {
                    @Override
                    public void publishIfRunning(OrionEvent event) {
                        assertReadable(executor, readerRef.get());
                    }
                });
                OrionKeyMaterial writer = open(store)) {
            readerRef.set(reader);
            writer.withModification(writer.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            reader.reload(store.load().revision());
            assertThat(reader.contains(SIGNING)).isTrue();
        }
    }

    @Test
    void acceptsItsOwnReloadWhileStoreWriteIsCompleting() throws Exception {
        AtomicReference<OrionKeyMaterial> materialRef =
                new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        InMemoryBlobStorage store = new InMemoryBlobStorage() {
            @Override
            public synchronized String write(BlobStorage.Modification modification) throws IOException {
                byte[] bytes = modification.bytes();
                String expectedRevision = modification.expectedRevision();
                String saved = super.write(new BlobStorage.Modification(expectedRevision, bytes));
                try {
                    materialRef.get().reload(saved);
                } catch (GeneralSecurityException failure) {
                    throw new IOException(failure);
                }
                return saved;
            }
        };
        OrionEventManager events = new OrionEventManager();
        events.subscribe(KeyMaterialChanged.class, event -> changes.incrementAndGet());
        events.onStart();
        try (OrionKeyMaterial material = open(store, events)) {
            materialRef.set(material);
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            assertThat(material.revision()).isEqualTo("1");
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
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            assertThatThrownBy(() -> OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event manager");
        }
    }

    @Test
    void savesMaterialWithAnUnstartedRunningAndStoppedEventManager() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        OrionEventManager events = new OrionEventManager();
        List<Set<String>> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        events.subscribe(KeyMaterialChanged.class, event -> changes.add(event.resources()));
        try (OrionKeyMaterial material = open(store, events)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            assertThat(changes).isEmpty();
            events.onStart();
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).containsExactly(Set.of(OTHER.alias()));
            events.onStop();
            material.withModification(material.revision(), modification -> {
                modification.delete(OTHER);
                modification.apply();
            });
            assertThat(changes).containsExactly(Set.of(OTHER.alias()));
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(material.contains(OTHER)).isFalse();
            assertThat(material.revision()).isEqualTo("3");
        } finally {
            if (events.isRunning()) events.onStop();
        }
    }

    @Test
    void inventoriesKeysAndTrustAnchorsByExactIdAfterReopening() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId cipher = new KeyMaterialId("cipher", 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Inventory root");
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey("cipher", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root.certificate());
                modification.apply();
            });
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
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        TestCertificateChain.Authority root = TestCertificateChain.root("Operation root");
        KeyMaterialId trustedIdentity = new KeyMaterialId("root", 1);
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
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
                assertThatThrownBy(() -> modification.installCertificateChain(trustedIdentity,
                        List.of(root.certificate()), Optional.empty()))
                        .isInstanceOf(IllegalArgumentException.class);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root.certificate());
                modification.apply();
            });
            assertThatThrownBy(() -> material.read(ROOT)
                    .keyPair()).isInstanceOf(GeneralSecurityException.class);
            assertThatThrownBy(() -> material.read(IDENTITY).trustedCertificate())
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("Trusted certificate");
            assertThatThrownBy(() -> new TlsMaterialConfiguration(trustedIdentity, Optional.empty(),
                    List.of(), TlsClientAuthentication.DISABLED)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TlsMaterialConfiguration(IDENTITY, Optional.of(IDENTITY),
                    List.of(), TlsClientAuthentication.DISABLED)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TlsMaterialConfiguration(IDENTITY, Optional.empty(),
                    List.of(IDENTITY), TlsClientAuthentication.REQUIRED)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void persistsPurposeSetsAndSearchesForAllRequestedFlagsAfterReload() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        Set<KeyMaterialPurpose> signingAndHost =
                Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST);
        Set<KeyMaterialPurpose> trustAndCa =
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        KeyMaterialId shared = new KeyMaterialId("shared", 1);
        KeyMaterialId trusted = new KeyMaterialId("shared-root", 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Shared root");
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey("shared", signingAndHost, KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate("shared-root", trustAndCa, KeyMaterialAlgorithm.RSA, root.certificate());
                modification.apply();
            });
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
            material.withModification(material.revision(), modification -> {
                modification.delete(signing);
                modification.apply();
            });
            assertThat(material.contains("shared", Set.of())).isFalse();

            assertThat(material.inventory()).doesNotContain(shared).hasSize(2);
        }
    }

    @Test
    void certificateUpdatesThroughSubsetDescriptorsPreserveEveryStoredPurpose() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        Set<KeyMaterialPurpose> identityPurposes =
                Set.of(KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialPurpose.SERVER_SIGNING);
        Set<KeyMaterialPurpose> rootPurposes =
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        KeyMaterialId fullIdentity = new KeyMaterialId(IDENTITY.alias(), IDENTITY.version());
        KeyMaterialId fullRoot = new KeyMaterialId(ROOT.alias(), ROOT.version());
        TestCertificateChain.Authority root = TestCertificateChain.root("Root");
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(IDENTITY.alias(), identityPurposes, KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate(ROOT.alias(), rootPurposes, KeyMaterialAlgorithm.RSA, root.certificate());
                modification.apply();
            });
            X509Certificate leaf =
                    TestCertificateChain.leaf("example.test", material.read(IDENTITY).keyPair(), root);
            material.withModification(material.revision(), modification -> {
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root.certificate());
                modification.installCertificateChain(IDENTITY, List.of(leaf), Optional.of(ROOT));
                modification.apply();
            });
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
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId host = new KeyMaterialId("ssh", 1);
        byte[] payload = "token".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification modification = material.modify(material.revision());
            modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            modification.generateKey(("ssh"), Set.of((KeyMaterialPurpose.SSH_HOST)), KeyMaterialAlgorithm.RSA, 2048);

            assertThat(material.hasDurableSnapshot()).isFalse();
            assertThat(material.revision()).isNull();
            assertThat(modification.version()).isNull();
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.inventory()).isEmpty();
            assertThat(store.load().bytes()).isNull();

            modification.apply();

            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.revision()).isEqualTo("1");
            assertThat(store.load().revision()).isEqualTo("1");

            assertThat(material.sshHostKeyPairs(List.of())).hasSize(1);
            signature = material.sign(SIGNING, payload);
            publicKey = material.read(SIGNING).publicKey().getEncoded();
            modification.discard();
            assertThatThrownBy(modification::apply).isInstanceOf(IllegalStateException.class);
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.hasDurableSnapshot()).isTrue();
            assertThat(reloaded.revision()).isEqualTo("1");
            assertThat(reloaded.read(SIGNING).publicKey().getEncoded()).isEqualTo(publicKey);
            assertThat(reloaded.verify(SIGNING, payload, signature)).isTrue();
            assertThat(reloaded.verify(SIGNING, new byte[]{1}, signature)).isFalse();
        }
    }

    @Test
    void reportsChangedAliasesOnlyAfterACommittedModification() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        List<KeyMaterialChanged> changes = new ArrayList<>();
        OrionEventManager events = new OrionEventManager();
        events.subscribe(KeyMaterialChanged.class, changes::add);
        events.onStart();
        try (OrionKeyMaterial material = open(store, events)) {
            KeyMaterialModification discarded = material.modify(material.revision());
            discarded.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            discarded.discard();
            material.modify(material.revision()).apply();
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).isEmpty();
            X509Certificate root = TestCertificateChain.root("Root").certificate();

            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root);
                modification.apply();
            });

            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).extracting(KeyMaterialChanged::resources)
                    .containsExactly(Set.of(SIGNING.alias(), ROOT.alias()));
        } finally {
            events.onStop();
        }
    }

    @Test
    void importsSecretKeyAndAuthenticatesEnvelopesAfterReload() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        ConfigurationSecretContext context = new ConfigurationSecretContext("github-token", "access-token");
        byte[] plaintext = "password".getBytes(StandardCharsets.UTF_8);
        KeyMaterialId cipher;
        ConfigurationSecretEnvelope envelope;
        try (OrionKeyMaterial material = open(store)) {
            cipher = material.configurationCipherKey();
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            KeyMaterialModification modification = material.modify(material.revision());
            modification.importSecretKey("cipher", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, generator.generateKey());
            modification.apply();
            envelope = material.seal(cipher, plaintext, context);
            assertThat(material.revision()).isEqualTo("1");
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
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyPair imported = TestCertificateChain.keyPair();
        char[] pem = ("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(imported.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n").toCharArray();
        char[] rejected = "invalid private key".toCharArray();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification modification = material.modify(material.revision());
            assertThatThrownBy(() -> modification.importKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, rejected))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(rejected).containsOnly('\0');
            modification.importKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, pem);
            assertThat(pem).containsOnly('\0');
            modification.apply();
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.read(SIGNING).publicKey().getEncoded())
                    .isEqualTo(imported.getPublic().getEncoded());
            assertThat(reloaded.contains(OTHER)).isFalse();
        }
    }

    @Test
    void installsPendingIdentityAndIssuerTogetherWithoutPublishingInvalidChain() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        KeyPair keyPair = TestCertificateChain.keyPair();
        X509Certificate leaf = TestCertificateChain.leaf("orion.example", keyPair, intermediate);
        X509Certificate wrongLeaf = TestCertificateChain.leaf(
                "other.example", TestCertificateChain.keyPair(), intermediate);
        List<X509Certificate> chain = List.of(leaf, intermediate.certificate());
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification modification = material.modify(material.revision());
            modification.importKey(("https"), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA, keyPair);
            modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root.certificate());
            assertThatThrownBy(() -> modification.installCertificateChain(
                    IDENTITY, List.of(wrongLeaf, intermediate.certificate()), Optional.of(ROOT)))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("public key");
            modification.installCertificateChain(IDENTITY, chain, Optional.of(ROOT));
            assertThat(material.contains(IDENTITY)).isFalse();
            assertThat(material.contains(ROOT)).isFalse();
            modification.apply();
            assertThat(material.revision()).isEqualTo("1");
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
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification discarded = material.modify(material.revision());
            discarded.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            discarded.discard();
            discarded.discard();

            assertThatThrownBy(discarded::apply).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> discarded.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.revision()).isNull();
            assertThat(store.load().bytes()).isNull();

            material.withModification(material.revision(), modification -> {
                modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            assertThat(material.contains(OTHER)).isTrue();
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.revision()).isEqualTo("1");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackWithoutApplyDiscardsChangesEvenWhenItThrows(boolean fail) throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = open(store)) {
            List<KeyMaterialModification> retained = new ArrayList<>();
            KeyMaterialModification.Operation operation = modification -> {
                retained.add(modification);
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                if (fail) throw new IOException("callback failed");
            };
            if (fail) {
                assertThatThrownBy(() -> material.withModification(material.revision(), operation))
                        .isInstanceOf(IOException.class).hasMessage("callback failed");
            } else {
                material.withModification(material.revision(), operation);
            }
            assertThatThrownBy(retained.getFirst()::apply).isInstanceOf(IllegalStateException.class);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.revision()).isNull();
            assertThat(store.load().bytes()).isNull();
        }
    }

    @Test
    void emptyAndRepeatedGenerationDoNotPublishNewVersions() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = open(store)) {
            material.modify(material.revision()).apply();
            assertThat(store.load().bytes()).isNull();
            assertThat(material.revision()).isNull();
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            String beforeRevision = store.load().revision();
            byte[] beforeBytes = store.read(beforeRevision);
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            assertThat(material.revision()).isEqualTo("1");
            assertThat(store.load().bytes()).isEqualTo(beforeBytes);
            assertThat(store.load().revision()).isEqualTo(beforeRevision);
        }
    }

    @Test
    void onlyOneConcurrentModificationCanApplyEvenForDifferentAliases() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification first = material.modify(material.revision());
            KeyMaterialModification second = material.modify(material.revision());
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
            assertThat(material.revision()).isEqualTo("1");
            assertThat(store.load().revision()).isEqualTo("1");
            assertThat(material.inventory()).hasSize(1);
            assertThatThrownBy(first::apply).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(second::apply).isInstanceOf(IllegalStateException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.inventory()).hasSize(1);
            assertThat(reloaded.revision()).isEqualTo("1");
        }
    }

    @Test
    void rejectsAnOutdatedRevisionBeforeCallingTheModificationOperation() throws Exception {
        InMemoryBlobStorage source = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = open(source)) {
            String previous = material.revision();
            KeyMaterialModification pending = material.modify(previous);
            material.withModification(previous, modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> material.withModification(previous, modification -> calls.incrementAndGet()))
                    .isInstanceOf(BlobStorageConflictException.class);
            assertThat(calls).hasValue(0);
            assertThatThrownBy(() -> material.modify(previous))
                    .isInstanceOf(BlobStorageConflictException.class);
            assertThatThrownBy(pending::apply).isInstanceOf(BlobStorageConflictException.class);
            assertThat(material.revision()).isEqualTo(source.load().revision()).isNotEqualTo(previous);
            assertThat(material.contains(SIGNING)).isTrue();
        }
    }

    @Test
    void usesTheSourceRevisionEvenWhenMaterialContentHasNotChanged() throws Exception {
        InMemoryBlobStorage source = new InMemoryBlobStorage();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            byte[] bytes = codec.encode(codec.create());
            try {
                source.write(new BlobStorage.Modification(null, bytes));
                source.write(new BlobStorage.Modification(source.load().revision(), bytes));
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
        try (OrionKeyMaterial material = open(source)) {
            assertThat(source.load().revision()).isEqualTo("2");
            assertThat(material.revision()).isEqualTo("2");
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            assertThat(material.revision()).isEqualTo("3");
            assertThat(source.load().revision()).isEqualTo("3");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handlesSourceEventsDuringSuccessfulAndFailedWrites(boolean responseLost) throws Exception {
        OrionEventManager events = new OrionEventManager();
        AtomicInteger notifications = new AtomicInteger();
        events.subscribe(KeyMaterialChanged.class, event -> notifications.incrementAndGet());
        events.onStart();
        InMemoryBlobStorage source = new InMemoryBlobStorage() {
            @Override
            public pro.deta.orion.util.Subscription subscribe(
                    java.util.function.Consumer<BlobChanged> subscriber) {
                return events.subscribe(BlobChanged.class, subscriber);
            }

            @Override
            public String write(BlobStorage.Modification modification) throws IOException {
                byte[] bytes = modification.bytes();
                String expectedRevision = modification.expectedRevision();
                String saved = super.write(new BlobStorage.Modification(expectedRevision, bytes));
                events.publishAndWait(new BlobChanged(expectedRevision, saved));
                if (responseLost) throw new IOException("Write response lost");
                return saved;
            }
        };
        try (OrionKeyMaterial material = open(source, events)) {
            KeyMaterialModification modification = material.modify(material.revision());
            modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            if (responseLost) {
                assertThatThrownBy(modification::apply)
                        .isInstanceOf(IOException.class).hasMessage("Write response lost");
                assertThatThrownBy(material::revision)
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            } else {
                modification.apply();
                assertThat(material.revision()).isEqualTo("1");
            }
            events.publishAndWait(new OrionEvent() {});
            assertThat(notifications).hasValue(responseLost ? 0 : 1);
        } finally {
            events.onStop();
        }
        try (OrionKeyMaterial reopened = open(source)) {
            assertThat(reopened.contains(SIGNING)).isTrue();
            assertThat(reopened.revision()).isEqualTo("1");
        }
    }

    @Test
    void rejectsAnOutdatedOwnerWithoutOverwritingTheStoredWinner() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial first = open(store); OrionKeyMaterial second = open(store)) {
            KeyMaterialModification winner = first.modify(first.revision());
            KeyMaterialModification loser = second.modify(second.revision());
            winner.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            loser.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            winner.apply();
            String beforeRevision = store.load().revision();
            byte[] beforeBytes = store.read(beforeRevision);

            assertThatThrownBy(loser::apply).isInstanceOf(BlobStorageConflictException.class);
            assertThatThrownBy(() -> second.contains(OTHER)).isInstanceOf(IllegalStateException.class);
            assertThat(first.contains(SIGNING)).isTrue();
            assertThat(store.load().bytes()).isEqualTo(beforeBytes);
            assertThat(store.load().revision()).isEqualTo(beforeRevision);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.contains(SIGNING)).isTrue();
            assertThat(reloaded.contains(OTHER)).isFalse();
            assertThat(reloaded.revision()).isEqualTo("1");
        }
    }

    @Test
    void deletionAppendsVersionsAndKeepsOldCertificatesAfterReload() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        X509Certificate original = TestCertificateChain.root("Original root").certificate();
        X509Certificate replacement = TestCertificateChain.root("Replacement root").certificate();
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, original);
                modification.apply();
            });
            publicKey = material.read(SIGNING).publicKey().getEncoded();
            material.withModification(material.revision(), modification -> {
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, replacement);
                modification.apply();
            });
            assertThat(material.read(new KeyMaterialId(ROOT.alias(),
                    2)).trustedCertificate()).isEqualTo(replacement);
            assertThat(material.revision()).isEqualTo("2");

            KeyMaterialModification deletion = material.modify(material.revision());
            deletion.delete(SIGNING);
            deletion.delete(ROOT);
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(material.read(new KeyMaterialId(ROOT.alias(),
                    2)).trustedCertificate()).isEqualTo(replacement);
            deletion.apply();

            assertThat(material.revision()).isEqualTo("3");
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.contains(ROOT)).isFalse();
            assertThat(material.inventory()).isEmpty();

            assertThatThrownBy(() -> material.sign(SIGNING, new byte[]{1}))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.revision()).isEqualTo("3");
            assertThat(reloaded.contains(SIGNING)).isFalse();
            assertThat(reloaded.contains(ROOT)).isFalse();
            assertThat(reloaded.inventory()).isEmpty();
            assertThatThrownBy(() -> reloaded.read(ROOT).trustedCertificate())
                    .isInstanceOf(GeneralSecurityException.class);
        }

        KeyStore history = KeyStore.getInstance("PKCS12");
        history.load(new ByteArrayInputStream(store.load().bytes()),
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
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            KeyMaterialModification modification = material.modify(material.revision());
            modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            modification.delete(SIGNING);
            store.fail = true;

            assertThatThrownBy(modification::apply).isInstanceOf(IOException.class).hasMessage("save failed");
            assertThatThrownBy(() -> material.contains(SIGNING)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(modification::apply).isInstanceOf(IllegalStateException.class);
        }
        store.fail = false;
        String durableRevision = store.load().revision();
        byte[] durableBytes = store.read(durableRevision);
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.revision()).isEqualTo(committed ? "2" : "1");
            assertThat(reloaded.contains(SIGNING)).isEqualTo(!committed);
            assertThat(reloaded.contains(OTHER)).isEqualTo(committed);
            KeyMaterialId available = committed ? OTHER : SIGNING;
            byte[] payload = new byte[]{1};
            assertThat(reloaded.verify(available, payload, reloaded.sign(available, payload))).isTrue();
            assertThat(store.load().bytes()).isEqualTo(durableBytes);
            assertThat(store.load().revision()).isEqualTo(durableRevision);
        }
    }

    @Test
    void rejectsMetadataMismatchWithoutChangingExistingKeys() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            String beforeRevision = store.load().revision();
            byte[] beforeBytes = store.read(beforeRevision);
            KeyMaterialId otherPurpose = new KeyMaterialId("signing", 1);
            KeyMaterialModification modification = material.modify(material.revision());
            assertThatThrownBy(() -> modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA, 2048))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            assertThatThrownBy(() -> material.contains(otherPurpose))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            modification.apply();

            assertThat(material.revision()).isEqualTo("1");
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(store.load().bytes()).isEqualTo(beforeBytes);
            assertThat(store.load().revision()).isEqualTo(beforeRevision);
        }
    }

    @Test
    void reloadsTheRequestedRevisionAndInvalidatesPreparedModifications() throws Exception {
        VersionedStore store = new VersionedStore();
        KeyMaterialId secret = new KeyMaterialId("wrapping", 1);
        X509Certificate root = TestCertificateChain.root("Reload root").certificate();
        List<KeyMaterialChanged> changes = new ArrayList<>();
        OrionEventManager events = new OrionEventManager();
        events.subscribe(KeyMaterialChanged.class, changes::add);
        events.onStart();
        try (OrionKeyMaterial reader = open(store, events);
             OrionKeyMaterial writer = open(store)) {
            writer.withModification(writer.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey("wrapping", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
                modification.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, root);
                modification.apply();
            });
            reader.reload(store.load().revision());
            KeyMaterialModification stale = reader.modify(reader.revision());
            stale.generateKey(("stale"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
            try {
                writer.withModification(writer.revision(), modification -> {
                    modification.generateKey(("other"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.apply();
                });
                String added = store.load().revision();
                writer.withModification(writer.revision(), modification -> {
                    modification.delete(OTHER);
                    modification.apply();
                });
                String deleted = store.load().revision();
                reader.reload(added);
                assertThat(reader.contains(OTHER)).isTrue();
                assertThat(reader.revision()).isEqualTo("2");
                assertThatThrownBy(stale::apply).isInstanceOf(BlobStorageConflictException.class);
                reader.reload(deleted);
                assertThat(reader.contains(OTHER)).isFalse();
                assertThat(reader.contains(SIGNING)).isTrue();
                assertThat(reader.contains(secret)).isTrue();
                assertThat(reader.contains(ROOT)).isTrue();
                assertThat(reader.revision()).isEqualTo("3");
                reader.reload(deleted);
                events.publishAndWait(new OrionEvent() {});
                assertThat(changes).extracting(KeyMaterialChanged::resources)
                        .containsExactly(Set.of("signing", "wrapping", "root"), Set.of("other"), Set.of("other"));
                assertThatThrownBy(() -> reader.reload(added)).isInstanceOf(GeneralSecurityException.class);
                assertThat(reader.revision()).isEqualTo("3");
                reader.withModification(reader.revision(), modification -> {
                    modification.generateKey(("after-reload"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.apply();
                });
                assertThat(reader.revision()).isEqualTo("4");
            } finally {
                stale.discard();
            }
        } finally {
            events.onStop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsWorkingKeysWhenAnExternalRevisionIsCorruptOrReplacesHistory(boolean corrupt) throws Exception {
        VersionedStore store = new VersionedStore();
        try (OrionKeyMaterial reader = open(store)) {
            reader.withModification(reader.revision(), modification -> {
                modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                modification.apply();
            });
            byte[] payload = new byte[]{1, 2, 3};
            byte[] signature = reader.sign(SIGNING, payload);
            byte[] replacement;
            InMemoryBlobStorage otherStore = new InMemoryBlobStorage();
            try (OrionKeyMaterial other = open(otherStore)) {
                other.withModification(other.revision(), modification -> {
                    modification.generateKey(("signing"), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), KeyMaterialAlgorithm.RSA, 2048);
                    modification.apply();
                });
                replacement = otherStore.load().bytes();
            }
            String revision = store.write(new BlobStorage.Modification(store.load().revision(),
                    corrupt ? new byte[]{1, 2, 3} : replacement));
            assertThatThrownBy(() -> reader.reload(revision)).isInstanceOfAny(
                    IOException.class, GeneralSecurityException.class);
            assertThat(reader.revision()).isEqualTo("1");
            assertThat(reader.verify(SIGNING, payload, signature)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesItsStoreOnCloseOrWhenLoadingFails(boolean invalidMaterial) throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InMemoryBlobStorage store = new InMemoryBlobStorage() {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        if (invalidMaterial) store.write(new BlobStorage.Modification(null, new byte[]{1, 2, 3}));
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            if (invalidMaterial) {
                assertThatThrownBy(() -> OrionKeyMaterial.open(new KeyMaterialRepository(
                        store,
                        new KeyMaterialCodec(options),
                        new OrionEventManager())))
                        .isInstanceOfAny(IOException.class, GeneralSecurityException.class);
            } else {
                OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                        store,
                        new KeyMaterialCodec(options),
                        new OrionEventManager()));
                material.close();
                material.close();
            }
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void remainsClosedWhenStoreCleanupFails() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InMemoryBlobStorage store = new InMemoryBlobStorage() {
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
        assertThatThrownBy(() -> material.modify(material.revision()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        material.close();
        assertThat(closes).hasValue(1);
    }

    private static final class VersionedStore extends InMemoryBlobStorage {
        private final Map<String, byte[]> history = new HashMap<>();

        @Override
        public synchronized String write(BlobStorage.Modification modification) throws IOException {
            byte[] bytes = modification.bytes();
            String expectedVersion = modification.expectedRevision();
            String revision = super.write(new BlobStorage.Modification(expectedVersion, bytes));
            history.put(revision, super.read(revision));
            return revision;
        }

        @Override
        public synchronized byte[] read(String revision) throws IOException {
            byte[] bytes = history.get(revision);
            if (bytes == null) throw new BlobStorageConflictException("Unknown revision: " + revision);
            return java.util.Arrays.copyOf(bytes, bytes.length);
        }
    }

    private static boolean applyTogether(KeyMaterialModification modification, CyclicBarrier start)
            throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            modification.apply();
            return true;
        } catch (BlobStorageConflictException expected) {
            return false;
        }
    }



    private static OrionKeyMaterial open(BlobStorage store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    new OrionEventManager()));
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

    private static final class FailingStore extends InMemoryBlobStorage {
        private final boolean committed;
        private boolean fail;

        private FailingStore(boolean committed) {
            this.committed = committed;
        }

        @Override
        public synchronized String write(BlobStorage.Modification modification) throws IOException {
            byte[] bytes = modification.bytes();
            String expectedVersion = modification.expectedRevision();
            if (fail) {
                if (committed) super.write(new BlobStorage.Modification(expectedVersion, bytes));
                throw new IOException("save failed");
            }
            return super.write(new BlobStorage.Modification(expectedVersion, bytes));
        }
    }
}
