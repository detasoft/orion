package pro.deta.orion.keymaterial;

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
    private static final TrustedCertificateDescriptor ROOT = new TrustedCertificateDescriptor(
            "root", KeyMaterialAlgorithm.RSA, 1);

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
            assertThat(material.inventory()).isEmpty();
            assertThat(store.read()).isEmpty();

            modification.apply();

            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.read().orElseThrow().version()).isEqualTo("1");
            assertThat(material.privateKeyDescriptors(KeyMaterialPurpose.SSH_HOST)).containsExactly(host);
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
            assertThat(store.read()).isEmpty();

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
            assertThat(store.read()).isEmpty();
        }
    }

    @Test
    void emptyAndRepeatedGenerationDoNotPublishNewVersions() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            material.modify().apply();
            assertThat(store.read()).isEmpty();
            assertThat(material.version()).isZero();
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            KeyMaterialSnapshot before = store.read().orElseThrow();
            material.withModification(modification -> {
                modification.generateKey(SIGNING, 2048);
                modification.apply();
            });
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.read().orElseThrow().bytes()).isEqualTo(before.bytes());
            assertThat(store.read().orElseThrow().version()).isEqualTo(before.version());
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
            assertThat(store.read().orElseThrow().version()).isEqualTo("1");
            assertThat(material.inventory()).hasSize(1);
            assertThatThrownBy(first::apply).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(second::apply).isInstanceOf(IllegalStateException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.inventory()).hasSize(1);
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
            KeyMaterialSnapshot before = store.read().orElseThrow();

            assertThatThrownBy(loser::apply).isInstanceOf(KeyMaterialStoreConflictException.class);
            assertThatThrownBy(() -> second.contains(OTHER)).isInstanceOf(IllegalStateException.class);
            assertThat(first.contains(SIGNING)).isTrue();
            assertThat(store.read().orElseThrow().bytes()).isEqualTo(before.bytes());
            assertThat(store.read().orElseThrow().version()).isEqualTo(before.version());
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
            assertThat(material.inventory()).isEmpty();
            assertThat(material.privateKeyDescriptors(KeyMaterialPurpose.SERVER_SIGNING)).isEmpty();
            assertThatThrownBy(() -> material.sign(SIGNING, new byte[]{1}))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.version()).isEqualTo(3);
            assertThat(reloaded.contains(SIGNING)).isFalse();
            assertThat(reloaded.contains(ROOT)).isFalse();
            assertThat(reloaded.inventory()).isEmpty();
            assertThatThrownBy(() -> reloaded.trustedCertificate(ROOT))
                    .isInstanceOf(GeneralSecurityException.class);
        }

        KeyStore history = KeyStore.getInstance("PKCS12");
        history.load(new ByteArrayInputStream(store.read().orElseThrow().bytes()),
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
        KeyMaterialSnapshot durable = store.read().orElseThrow();
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.version()).isEqualTo(committed ? 2 : 1);
            assertThat(reloaded.contains(SIGNING)).isEqualTo(!committed);
            assertThat(reloaded.contains(OTHER)).isEqualTo(committed);
            KeyMaterialDescriptor available = committed ? OTHER : SIGNING;
            byte[] payload = new byte[]{1};
            assertThat(reloaded.verify(available, payload, reloaded.sign(available, payload))).isTrue();
            assertThat(store.read().orElseThrow().bytes()).isEqualTo(durable.bytes());
            assertThat(store.read().orElseThrow().version()).isEqualTo(durable.version());
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
            KeyMaterialSnapshot before = store.read().orElseThrow();
            KeyMaterialDescriptor otherPurpose = descriptor("signing", KeyMaterialPurpose.TLS_IDENTITY);
            KeyMaterialModification modification = material.modify();
            assertThatThrownBy(() -> modification.generateKey(otherPurpose, 2048))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            assertThatThrownBy(() -> material.contains(otherPurpose))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
            modification.apply();

            assertThat(material.version()).isEqualTo(1);
            assertThat(material.contains(SIGNING)).isTrue();
            assertThat(store.read().orElseThrow().bytes()).isEqualTo(before.bytes());
            assertThat(store.read().orElseThrow().version()).isEqualTo(before.version());
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
        return new KeyMaterialDescriptor(alias, purpose,
                KeyMaterialAlgorithm.RSA, 1);
    }

    private static OrionKeyMaterial open(KeyMaterialContentStore store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(store, options);
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
