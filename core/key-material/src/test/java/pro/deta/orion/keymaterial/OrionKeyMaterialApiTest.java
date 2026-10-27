package pro.deta.orion.keymaterial;

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

    private static final KeyMaterialDescriptor SIGNING =
            descriptor("signing", KeyMaterialPurpose.SERVER_SIGNING);
    private static final KeyMaterialDescriptor OTHER = descriptor("other", KeyMaterialPurpose.SERVER_SIGNING);
    private static final KeyMaterialDescriptor IDENTITY = descriptor("https", KeyMaterialPurpose.TLS_IDENTITY);
    private static final KeyMaterialDescriptor ROOT = new KeyMaterialDescriptor(
            "root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, 1);

    @Test
    void releasesStateMonitorDuringStoreWriteAndChangePublication() throws Exception {
        AtomicReference<OrionKeyMaterial> materialRef =
                new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore() {
                @Override
                public synchronized String write(byte[] bytes, String expectedRevision) throws IOException {
                    assertReadable(executor, materialRef.get());
                    return super.write(bytes, expectedRevision);
                }
            };
            try (OrionKeyMaterial material = open(store, new OrionEventManager() {
                @Override
                public void publishIfRunning(OrionEvent event) {
                    assertReadable(executor, materialRef.get());
                }
            })) {
                materialRef.set(material);
                material.withModification(modification -> {
                    modification.generateKey(SIGNING, 2048);
                    modification.apply();
                });
                assertThat(material.version()).isEqualTo(1);
                assertThat(material.contains(SIGNING)).isTrue();
            }
        }
    }

    @Test
    void releasesStateMonitorBeforePublishingReloadChanges() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
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
            writer.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            reader.reload(store.revision().orElseThrow());
            assertThat(reader.contains(SIGNING)).isTrue();
        }
    }

    @Test
    void acceptsItsOwnReloadWhileStoreWriteIsCompleting() throws Exception {
        AtomicReference<OrionKeyMaterial> materialRef =
                new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore() {
            @Override
            public synchronized String write(byte[] bytes, String expectedRevision) throws IOException {
                String saved = super.write(bytes, expectedRevision);
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
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            assertThat(material.version()).isEqualTo(1);
            assertThat(material.contains(SIGNING)).isTrue();
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).hasValue(1);
        } finally {
            events.onStop();
        }
    }

    private static void assertReadable(ExecutorService executor, OrionKeyMaterial material) {
        try {
            executor.submit(material::version).get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("KMS state monitor is held during publication or persistence", failure);
        }
    }

    @Test
    void rejectsOpeningMaterialWithoutAnEventManager() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            assertThatThrownBy(() -> OrionKeyMaterial.open(store, options, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event manager");
        }
    }

    @Test
    void savesMaterialWithAnUnstartedRunningAndStoppedEventManager() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        OrionEventManager events = new OrionEventManager();
        List<Set<String>> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        events.subscribe(KeyMaterialChanged.class, event -> changes.add(event.resources()));
        try (OrionKeyMaterial material = open(store, events)) {
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            assertThat(changes).isEmpty();
            events.onStart();
            material.withModification(modification -> {
                modification.generateKey(OTHER, 2048);
                modification.apply();
            });
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).containsExactly(Set.of(OTHER.alias()));
            events.onStop();
            material.withModification(modification -> {
                modification.delete(OTHER);
                modification.apply();
            });
            assertThat(changes).containsExactly(Set.of(OTHER.alias()));
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(material.contains(OTHER)).isFalse();
            assertThat(material.version()).isEqualTo(3);
        } finally {
            if (events.isRunning()) events.onStop();
        }
    }

    @Test
    void inventoriesKeysAndTrustAnchorsThroughTheSameDescriptorAfterReopening() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor cipher = new KeyMaterialDescriptor("cipher",
                Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Inventory root");
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.generateKey(cipher, 256);
                modification.setTrustedCertificate(ROOT, root.certificate());
                modification.apply();
            });
        }
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.inventory(Set.of())).extracting(KeyMaterialInventoryEntry::descriptor)
                    .containsExactlyInAnyOrder(SIGNING, cipher, ROOT);
            assertThat(material.contains(ROOT)).isTrue();
            assertThat(material.trustedCertificate(ROOT)).isEqualTo(root.certificate());
            assertThat(material.keyPair(SIGNING).getPrivate()).isNotNull();
            assertThat(material.inventory(Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER)).getFirst().publicKeyPem())
                    .isNull();
        }
    }

    @Test
    void enforcesEntryKindsAtOperationsUsingTheCommonDescriptor() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        TestCertificateChain.Authority root = TestCertificateChain.root("Operation root");
        KeyMaterialDescriptor trustedIdentity = new KeyMaterialDescriptor("root",
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.TLS_IDENTITY),
                KeyMaterialAlgorithm.RSA, 1);
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(modification -> {
                assertThatThrownBy(() -> modification.generateKey(ROOT, 2048))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Trust anchors");
                assertThatThrownBy(() -> modification.importKey(ROOT, root.keyPair()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Trust anchors");
                assertThatThrownBy(() -> modification.setTrustedCertificate(SIGNING, root.certificate()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRUST_ANCHOR");
                modification.generateKey(IDENTITY, 2048);
                assertThatThrownBy(() -> modification.installCertificateChain(IDENTITY,
                        List.of(root.certificate()), Optional.of(IDENTITY)))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRUST_ANCHOR");
                assertThatThrownBy(() -> modification.installCertificateChain(trustedIdentity,
                        List.of(root.certificate()), Optional.empty()))
                        .isInstanceOf(IllegalArgumentException.class);
                modification.setTrustedCertificate(ROOT, root.certificate());
                modification.apply();
            });
            assertThatThrownBy(() -> material.keyPair(ROOT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> material.trustedCertificate(IDENTITY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRUST_ANCHOR");
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
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        Set<KeyMaterialPurpose> signingAndHost =
                Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST);
        Set<KeyMaterialPurpose> trustAndCa =
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        KeyMaterialDescriptor shared = new KeyMaterialDescriptor("shared", signingAndHost,
                KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor trusted = new KeyMaterialDescriptor("shared-root", trustAndCa,
                KeyMaterialAlgorithm.RSA, 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Shared root");
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(modification -> {
                modification.generateKey(shared, 2048);
                modification.generateKey(OTHER, 2048);
                modification.setTrustedCertificate(trusted, root.certificate());
                modification.apply();
            });
        }
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.privateKeyDescriptors(signingAndHost)).containsExactly(shared);
            assertThat(material.privateKeyDescriptors(Set.of(KeyMaterialPurpose.SERVER_SIGNING)))
                    .containsExactlyInAnyOrder(shared, OTHER);
            assertThat(material.inventory(trustAndCa)).extracting(entry -> entry.descriptor().alias())
                    .containsExactly("shared-root");
            assertThat(material.contains("shared", signingAndHost)).isTrue();
            assertThat(material.contains("shared", trustAndCa)).isFalse();
            assertThat(material.contains("missing", signingAndHost)).isFalse();
            KeyMaterialDescriptor signing = new KeyMaterialDescriptor("shared",
                    Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
            byte[] payload = "multi-purpose key".getBytes(StandardCharsets.UTF_8);
            assertThat(material.verify(signing, payload, material.sign(signing, payload))).isTrue();
            material.withModification(modification -> {
                modification.delete(signing);
                modification.apply();
            });
            assertThat(material.contains("shared", Set.of())).isFalse();
            assertThat(material.privateKeyDescriptors(signingAndHost)).isEmpty();
            assertThat(material.inventory(signingAndHost)).isEmpty();
            assertThat(material.inventory(Set.of())).hasSize(2);
        }
    }

    @Test
    void certificateUpdatesThroughSubsetDescriptorsPreserveEveryStoredPurpose() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        Set<KeyMaterialPurpose> identityPurposes =
                Set.of(KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialPurpose.SERVER_SIGNING);
        Set<KeyMaterialPurpose> rootPurposes =
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        KeyMaterialDescriptor fullIdentity = new KeyMaterialDescriptor(IDENTITY.alias(), identityPurposes,
                IDENTITY.algorithm(), IDENTITY.version());
        KeyMaterialDescriptor fullRoot = new KeyMaterialDescriptor(ROOT.alias(), rootPurposes,
                ROOT.algorithm(), ROOT.version());
        TestCertificateChain.Authority root = TestCertificateChain.root("Root");
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(modification -> {
                modification.generateKey(fullIdentity, 2048);
                modification.setTrustedCertificate(fullRoot, root.certificate());
                modification.apply();
            });
            X509Certificate leaf = TestCertificateChain.leaf("example.test", material.keyPair(IDENTITY), root);
            material.withModification(modification -> {
                modification.setTrustedCertificate(ROOT, root.certificate());
                modification.installCertificateChain(IDENTITY, List.of(leaf), Optional.of(ROOT));
                modification.apply();
            });
        }
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.inventory(identityPurposes)).extracting(entry -> entry.descriptor().purposes())
                    .containsExactly(identityPurposes);
            assertThat(material.inventory(rootPurposes)).extracting(entry -> entry.descriptor().purposes())
                    .containsExactly(rootPurposes);
            assertThat(material.certificateChain(fullIdentity)).hasSize(1);
            assertThat(material.trustedCertificate(fullRoot)).isEqualTo(root.certificate());
            assertThat(material.createTlsContext(new TlsMaterialConfiguration(IDENTITY, Optional.of(ROOT),
                    List.of(fullRoot), TlsClientAuthentication.WANT)).getProtocol()).isEqualTo("TLS");
        }
    }

    @Test
    void publishesGeneratedKeysTogetherAndSignsAfterReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor host = descriptor("ssh", KeyMaterialPurpose.SSH_HOST);
        byte[] payload = "token".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification modification = material.modify();
            modification.generateKey(SIGNING, 2048);
            modification.generateKey(host, 2048);

            assertThat(material.hasDurableSnapshot()).isFalse();
            assertThat(material.version()).isZero();
            assertThat(modification.version()).isEqualTo(1);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.inventory(Set.of())).isEmpty();
            assertThat(store.revision()).isEmpty();

            modification.apply();

            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.revision().orElseThrow()).isEqualTo("1");
            assertThat(material.privateKeyDescriptors(Set.of(KeyMaterialPurpose.SSH_HOST))).containsExactly(host);
            assertThat(material.sshHostKeyPairs(List.of())).hasSize(1);
            signature = material.sign(SIGNING, payload);
            publicKey = material.publicKey(SIGNING).getEncoded();
            modification.discard();
            assertThatThrownBy(modification::apply).isInstanceOf(IllegalStateException.class);
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.hasDurableSnapshot()).isTrue();
            assertThat(reloaded.version()).isEqualTo(1);
            assertThat(reloaded.publicKey(SIGNING).getEncoded()).isEqualTo(publicKey);
            assertThat(reloaded.verify(SIGNING, payload, signature)).isTrue();
            assertThat(reloaded.verify(SIGNING, new byte[]{1}, signature)).isFalse();
        }
    }

    @Test
    void reportsChangedAliasesOnlyAfterACommittedModification() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        List<KeyMaterialChanged> changes = new ArrayList<>();
        OrionEventManager events = new OrionEventManager();
        events.subscribe(KeyMaterialChanged.class, changes::add);
        events.onStart();
        try (OrionKeyMaterial material = open(store, events)) {
            KeyMaterialModification discarded = material.modify();
            discarded.generateKey(SIGNING, 2048);
            discarded.discard();
            material.modify().apply();
            events.publishAndWait(new OrionEvent() {});
            assertThat(changes).isEmpty();
            X509Certificate root = TestCertificateChain.root("Root").certificate();

            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.setTrustedCertificate(ROOT, root);
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
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        ConfigurationSecretContext context = new ConfigurationSecretContext("github-token", "access-token");
        byte[] plaintext = "password".getBytes(StandardCharsets.UTF_8);
        KeyMaterialDescriptor cipher;
        ConfigurationSecretEnvelope envelope;
        try (OrionKeyMaterial material = open(store)) {
            cipher = material.configurationCipherKey();
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            KeyMaterialModification modification = material.modify();
            modification.importSecretKey(cipher, generator.generateKey());
            modification.apply();
            envelope = material.seal(cipher, plaintext, context);
            assertThat(material.version()).isEqualTo(1);
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
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyPair imported = TestCertificateChain.keyPair();
        char[] pem = ("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(imported.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n").toCharArray();
        char[] rejected = "invalid private key".toCharArray();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification modification = material.modify();
            assertThatThrownBy(() -> modification.importKey(OTHER, rejected))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(rejected).containsOnly('\0');
            modification.importKey(SIGNING, pem);
            assertThat(pem).containsOnly('\0');
            modification.apply();
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.publicKey(SIGNING).getEncoded()).isEqualTo(imported.getPublic().getEncoded());
            assertThat(reloaded.contains(OTHER)).isFalse();
        }
    }

    @Test
    void installsPendingIdentityAndIssuerTogetherWithoutPublishingInvalidChain() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        KeyPair keyPair = TestCertificateChain.keyPair();
        X509Certificate leaf = TestCertificateChain.leaf("orion.example", keyPair, intermediate);
        X509Certificate wrongLeaf = TestCertificateChain.leaf(
                "other.example", TestCertificateChain.keyPair(), intermediate);
        List<X509Certificate> chain = List.of(leaf, intermediate.certificate());
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification modification = material.modify();
            modification.importKey(IDENTITY, keyPair);
            modification.setTrustedCertificate(ROOT, root.certificate());
            assertThatThrownBy(() -> modification.installCertificateChain(
                    IDENTITY, List.of(wrongLeaf, intermediate.certificate()), Optional.of(ROOT)))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("public key");
            modification.installCertificateChain(IDENTITY, chain, Optional.of(ROOT));
            assertThat(material.issuedCertificateChain(IDENTITY)).isEmpty();
            assertThat(material.contains(ROOT)).isFalse();
            modification.apply();
            assertThat(material.version()).isEqualTo(1);
            assertThat(material.issuedCertificateChain(IDENTITY).orElseThrow())
                    .containsExactlyElementsOf(chain);
        }

        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.certificateChain(IDENTITY)).containsExactlyElementsOf(chain);
            assertThat(reloaded.trustedCertificate(ROOT)).isEqualTo(root.certificate());
            TlsMaterialConfiguration tls = new TlsMaterialConfiguration(
                    IDENTITY, Optional.of(ROOT), List.of(), TlsClientAuthentication.DISABLED);
            assertThat(reloaded.createTlsContext(tls).getSocketFactory().getSupportedCipherSuites())
                    .isNotEmpty();
        }
    }

    @Test
    void discardsChangesAndKeepsTheOwnerUsable() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification discarded = material.modify();
            discarded.generateKey(SIGNING, 2048);
            discarded.discard();
            discarded.discard();

            assertThatThrownBy(discarded::apply).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> discarded.generateKey(SIGNING, 2048))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.version()).isZero();
            assertThat(store.revision()).isEmpty();

            material.withModification(modification -> {
                modification.generateKey(OTHER, 2048);
                modification.apply();
            });
            assertThat(material.contains(OTHER)).isTrue();
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.version()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackWithoutApplyDiscardsChangesEvenWhenItThrows(boolean fail) throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            List<KeyMaterialModification> retained = new ArrayList<>();
            KeyMaterialModification.Operation operation = modification -> {
                retained.add(modification);
                modification.generateKey(SIGNING, 2048);
                if (fail) throw new IOException("callback failed");
            };
            if (fail) {
                assertThatThrownBy(() -> material.withModification(operation))
                        .isInstanceOf(IOException.class).hasMessage("callback failed");
            } else {
                material.withModification(operation);
            }
            assertThatThrownBy(retained.getFirst()::apply).isInstanceOf(IllegalStateException.class);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.version()).isZero();
            assertThat(store.revision()).isEmpty();
        }
    }

    @Test
    void emptyAndRepeatedGenerationDoNotPublishNewVersions() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            material.modify().apply();
            assertThat(store.revision()).isEmpty();
            assertThat(material.version()).isZero();
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            String beforeRevision = store.revision().orElseThrow();
            byte[] beforeBytes = store.read(beforeRevision);
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.read(store.revision().orElseThrow())).isEqualTo(beforeBytes);
            assertThat(store.revision().orElseThrow()).isEqualTo(beforeRevision);
        }
    }

    @Test
    void onlyOneConcurrentModificationCanApplyEvenForDifferentAliases() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            KeyMaterialModification first = material.modify();
            KeyMaterialModification second = material.modify();
            first.generateKey(SIGNING, 2048);
            second.generateKey(OTHER, 2048);
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
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.revision().orElseThrow()).isEqualTo("1");
            assertThat(material.inventory(Set.of())).hasSize(1);
            assertThatThrownBy(first::apply).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(second::apply).isInstanceOf(IllegalStateException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.inventory(Set.of())).hasSize(1);
            assertThat(reloaded.version()).isEqualTo(1);
        }
    }

    @Test
    void rejectsAnOutdatedOwnerWithoutOverwritingTheStoredWinner() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial first = open(store); OrionKeyMaterial second = open(store)) {
            KeyMaterialModification winner = first.modify();
            KeyMaterialModification loser = second.modify();
            winner.generateKey(SIGNING, 2048);
            loser.generateKey(OTHER, 2048);
            winner.apply();
            String beforeRevision = store.revision().orElseThrow();
            byte[] beforeBytes = store.read(beforeRevision);

            assertThatThrownBy(loser::apply).isInstanceOf(KeyMaterialStoreConflictException.class);
            assertThatThrownBy(() -> second.contains(OTHER)).isInstanceOf(IllegalStateException.class);
            assertThat(first.contains(SIGNING)).isTrue();
            assertThat(store.read(store.revision().orElseThrow())).isEqualTo(beforeBytes);
            assertThat(store.revision().orElseThrow()).isEqualTo(beforeRevision);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.contains(SIGNING)).isTrue();
            assertThat(reloaded.contains(OTHER)).isFalse();
            assertThat(reloaded.version()).isEqualTo(1);
        }
    }

    @Test
    void deletionAppendsVersionsAndKeepsOldCertificatesAfterReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        X509Certificate original = TestCertificateChain.root("Original root").certificate();
        X509Certificate replacement = TestCertificateChain.root("Replacement root").certificate();
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.setTrustedCertificate(ROOT, original);
                modification.apply();
            });
            publicKey = material.publicKey(SIGNING).getEncoded();
            material.withModification(modification -> {
                modification.setTrustedCertificate(ROOT, replacement);
                modification.apply();
            });
            assertThat(material.trustedCertificate(ROOT)).isEqualTo(replacement);
            assertThat(material.version()).isEqualTo(2);

            KeyMaterialModification deletion = material.modify();
            deletion.delete(SIGNING);
            deletion.delete(ROOT);
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(material.trustedCertificate(ROOT)).isEqualTo(replacement);
            deletion.apply();

            assertThat(material.version()).isEqualTo(3);
            assertThat(material.contains(SIGNING)).isFalse();
            assertThat(material.contains(ROOT)).isFalse();
            assertThat(material.inventory(Set.of())).isEmpty();
            assertThat(material.privateKeyDescriptors(Set.of(KeyMaterialPurpose.SERVER_SIGNING))).isEmpty();
            assertThatThrownBy(() -> material.sign(SIGNING, new byte[]{1}))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.version()).isEqualTo(3);
            assertThat(reloaded.contains(SIGNING)).isFalse();
            assertThat(reloaded.contains(ROOT)).isFalse();
            assertThat(reloaded.inventory(Set.of())).isEmpty();
            assertThatThrownBy(() -> reloaded.trustedCertificate(ROOT))
                    .isInstanceOf(GeneralSecurityException.class);
        }

        KeyStore history = KeyStore.getInstance("PKCS12");
        history.load(new ByteArrayInputStream(store.read(store.revision().orElseThrow())),
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
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            KeyMaterialModification modification = material.modify();
            modification.generateKey(OTHER, 2048);
            modification.delete(SIGNING);
            store.fail = true;

            assertThatThrownBy(modification::apply).isInstanceOf(IOException.class).hasMessage("save failed");
            assertThatThrownBy(() -> material.contains(SIGNING)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(modification::apply).isInstanceOf(IllegalStateException.class);
        }
        store.fail = false;
        String durableRevision = store.revision().orElseThrow();
        byte[] durableBytes = store.read(durableRevision);
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.version()).isEqualTo(committed ? 2 : 1);
            assertThat(reloaded.contains(SIGNING)).isEqualTo(!committed);
            assertThat(reloaded.contains(OTHER)).isEqualTo(committed);
            KeyMaterialDescriptor available = committed ? OTHER : SIGNING;
            byte[] payload = new byte[]{1};
            assertThat(reloaded.verify(available, payload, reloaded.sign(available, payload))).isTrue();
            assertThat(store.read(store.revision().orElseThrow())).isEqualTo(durableBytes);
            assertThat(store.revision().orElseThrow()).isEqualTo(durableRevision);
        }
    }

    @Test
    void rejectsMetadataMismatchWithoutChangingExistingKeys() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            String beforeRevision = store.revision().orElseThrow();
            byte[] beforeBytes = store.read(beforeRevision);
            KeyMaterialDescriptor otherPurpose = descriptor("signing", KeyMaterialPurpose.TLS_IDENTITY);
            KeyMaterialModification modification = material.modify();
            assertThatThrownBy(() -> modification.generateKey(otherPurpose, 2048))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            assertThatThrownBy(() -> material.contains(otherPurpose))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            modification.apply();

            assertThat(material.version()).isEqualTo(1);
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(store.read(store.revision().orElseThrow())).isEqualTo(beforeBytes);
            assertThat(store.revision().orElseThrow()).isEqualTo(beforeRevision);
        }
    }

    @Test
    void reloadsTheRequestedRevisionAndInvalidatesPreparedModifications() throws Exception {
        VersionedStore store = new VersionedStore();
        KeyMaterialDescriptor secret = new KeyMaterialDescriptor("wrapping",
                Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 1);
        X509Certificate root = TestCertificateChain.root("Reload root").certificate();
        List<KeyMaterialChanged> changes = new ArrayList<>();
        OrionEventManager events = new OrionEventManager();
        events.subscribe(KeyMaterialChanged.class, changes::add);
        events.onStart();
        try (OrionKeyMaterial reader = open(store, events);
             OrionKeyMaterial writer = open(store)) {
            writer.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.generateKey(secret, 256);
                modification.setTrustedCertificate(ROOT, root);
                modification.apply();
            });
            reader.reload(store.revision().orElseThrow());
            KeyMaterialModification stale = reader.modify();
            stale.generateKey(descriptor("stale", KeyMaterialPurpose.SERVER_SIGNING), 2048);
            try {
                writer.withModification(modification -> {
                    modification.generateKey(OTHER, 2048);
                    modification.apply();
                });
                String added = store.revision().orElseThrow();
                writer.withModification(modification -> {
                    modification.delete(OTHER);
                    modification.apply();
                });
                String deleted = store.revision().orElseThrow();
                reader.reload(added);
                assertThat(reader.contains(OTHER)).isTrue();
                assertThat(reader.version()).isEqualTo(2);
                assertThatThrownBy(stale::apply).isInstanceOf(KeyMaterialStoreConflictException.class);
                reader.reload(deleted);
                assertThat(reader.contains(OTHER)).isFalse();
                assertThat(reader.contains(SIGNING)).isTrue();
                assertThat(reader.contains(secret)).isTrue();
                assertThat(reader.contains(ROOT)).isTrue();
                assertThat(reader.version()).isEqualTo(3);
                reader.reload(deleted);
                events.publishAndWait(new OrionEvent() {});
                assertThat(changes).extracting(KeyMaterialChanged::resources)
                        .containsExactly(Set.of("signing", "wrapping", "root"), Set.of("other"), Set.of("other"));
                assertThatThrownBy(() -> reader.reload(added)).isInstanceOf(GeneralSecurityException.class);
                assertThat(reader.version()).isEqualTo(3);
                reader.withModification(modification -> {
                    modification.generateKey(descriptor("after-reload", KeyMaterialPurpose.SERVER_SIGNING), 2048);
                    modification.apply();
                });
                assertThat(reader.version()).isEqualTo(4);
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
            reader.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            byte[] payload = new byte[]{1, 2, 3};
            byte[] signature = reader.sign(SIGNING, payload);
            byte[] replacement;
            InMemoryKeyMaterialContentStore otherStore = new InMemoryKeyMaterialContentStore();
            try (OrionKeyMaterial other = open(otherStore)) {
                other.withModification(modification -> {
                    modification.generateKey(SIGNING, 2048);
                    modification.apply();
                });
                replacement = otherStore.read(otherStore.revision().orElseThrow());
            }
            String revision = store.write(corrupt ? new byte[]{1, 2, 3} : replacement,
                    store.revision().orElseThrow());
            assertThatThrownBy(() -> reader.reload(revision)).isInstanceOfAny(
                    IOException.class, GeneralSecurityException.class);
            assertThat(reader.version()).isEqualTo(1);
            assertThat(reader.verify(SIGNING, payload, signature)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesItsStoreOnCloseOrWhenLoadingFails(boolean invalidMaterial) throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore() {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        if (invalidMaterial) store.write(new byte[]{1, 2, 3}, null);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            if (invalidMaterial) {
                assertThatThrownBy(() -> OrionKeyMaterial.open(store, options, new OrionEventManager()))
                        .isInstanceOfAny(IOException.class, GeneralSecurityException.class);
            } else {
                OrionKeyMaterial material = OrionKeyMaterial.open(store, options, new OrionEventManager());
                material.close();
                material.close();
            }
        }
        assertThat(closes).hasValue(1);
    }

    private static final class VersionedStore extends InMemoryKeyMaterialContentStore {
        private final Map<String, byte[]> history = new HashMap<>();

        @Override
        public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
            String revision = super.write(bytes, expectedVersion);
            history.put(revision, super.read(revision));
            return revision;
        }

        @Override
        public synchronized byte[] read(String revision) throws IOException {
            byte[] bytes = history.get(revision);
            if (bytes == null) throw new KeyMaterialStoreConflictException("Unknown revision: " + revision);
            return java.util.Arrays.copyOf(bytes, bytes.length);
        }
    }

    private static boolean applyTogether(KeyMaterialModification modification, CyclicBarrier start)
            throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            modification.apply();
            return true;
        } catch (KeyMaterialStoreConflictException expected) {
            return false;
        }
    }

    private static KeyMaterialDescriptor descriptor(String alias, KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(alias, Set.of(purpose),
                KeyMaterialAlgorithm.RSA, 1);
    }

    private static OrionKeyMaterial open(KeyMaterialContentStore store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(store, options,
                    new OrionEventManager());
        }
    }

    private static OrionKeyMaterial open(KeyMaterialContentStore store, OrionEventManager events)
            throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(store, options, events);
        }
    }

    private static final class FailingStore extends InMemoryKeyMaterialContentStore {
        private final boolean committed;
        private boolean fail;

        private FailingStore(boolean committed) {
            this.committed = committed;
        }

        @Override
        public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
            if (fail) {
                if (committed) super.write(bytes, expectedVersion);
                throw new IOException("save failed");
            }
            return super.write(bytes, expectedVersion);
        }
    }
}
