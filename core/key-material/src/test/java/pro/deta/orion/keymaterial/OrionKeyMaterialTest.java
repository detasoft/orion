package pro.deta.orion.keymaterial;

import pro.deta.orion.event.OrionEventManager;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionKeyMaterialTest {
    @Test
    void opensAnEmptyStoreWithoutCreatingKeysAndPersistsAnExplicitModification() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            assertThat(material.inventory(Set.of())).isEmpty();
            assertThat(material.version()).isZero();
            assertThat(store.revision()).isEmpty();
            material.withModification(modification -> {
                modification.generateKey(signing, 2048);
                assertThat(material.contains(signing)).isFalse();
                assertThat(store.revision()).isEmpty();
                modification.apply();
            });
            signature = material.sign(signing, payload);
            assertThat(material.version()).isEqualTo(1);
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            assertThat(material.verify(signing, payload, signature)).isTrue();
        }
    }

    @Test
    void retainsSigningKeysAcrossReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor old = rsa("server-signing-v1", 1);
        KeyMaterialDescriptor active = rsa("server-signing-v2", 2);
        byte[] payload = "retained-jwt".getBytes(StandardCharsets.UTF_8);
        byte[] oldSignature;
        try (KeyMaterialService service = KeyMaterialService.open(store, options(),
                new OrionEventManager())) {
            KeyMaterialModification modification = service.modify();
            try {
                modification.generateKey(old, 2048);
                modification.generateKey(active, 2048);
                modification.apply();
            } finally {
                modification.discard();
            }
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            oldSignature = material.sign(old, payload);
        }

        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            byte[] activeSignature = material.sign(active, payload);

            assertThat(material.verify(old, payload, oldSignature)).isTrue();
            assertThat(material.verify(active, payload, activeSignature)).isTrue();
            assertThat(material.verify(old, payload, activeSignature)).isFalse();
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            assertThat(material.verify(old, payload, oldSignature)).isTrue();
        }
    }

    @Test
    void sharesInstalledCertificateIdentityWithTls() throws Exception {
        CountingStore store = new CountingStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        OrionKeyMaterial material = openWithKey(store, signing);
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor identity = descriptor(
                "https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1);
        TlsMaterialConfiguration tls = new TlsMaterialConfiguration(
                identity, Optional.empty(), List.of(), TlsClientAuthentication.DISABLED);

        byte[] payload = new byte[]{1};
        byte[] signature = material.sign(signing, payload);
        generateKeys(material, account, identity);
        CertificateIdentity certificate = material.certificateIdentity(tls);
        assertThatThrownBy(certificate::createContext)
                .hasMessageContaining("issued certificate chain");
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        material.certificateIdentity(tls).installCertificateChain(
                List.of(TestCertificateChain.leaf("orion.example", material.keyPair(identity), intermediate)),
                Optional.empty());

        assertThat(material.verify(signing, payload, signature)).isTrue();
        assertThat(material.certificateIdentity(tls).issuedCertificateChain().orElseThrow()).hasSize(1);
        assertThat(certificate.createContext().getProtocol()).isEqualTo("TLS");
        assertThat(store.readCalls).isEqualTo(1);
        KeyMaterialDescriptor staleAccount = new KeyMaterialDescriptor(account.alias(), account.purposes(),
                account.algorithm(), 2);
        assertThatThrownBy(() -> material.keyPair(staleAccount))
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("version");

        material.close();
        material.close();
        assertThatThrownBy(certificate::createContext)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> generateKeys(material, account, identity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    private static KeyMaterialDescriptor rsa(String alias, long version) {
        return descriptor(alias, KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, version);
    }

    @Test
    void publishesTheLazyCipherKeyOnlyAfterTheStoreAcceptsIt() throws Exception {
        class ObservingStore extends InMemoryKeyMaterialContentStore {
            private OrionKeyMaterial material;
            private int writes;

            @Override
            public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
                try {
                    assertThat(material.contains(material.configurationCipherKey())).isFalse();
                } catch (GeneralSecurityException failure) {
                    throw new IOException(failure);
                }
                writes++;
                return super.write(bytes, expectedVersion);
            }
        }
        ObservingStore store = new ObservingStore();
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (KeyMaterialOptions options = options(); OrionKeyMaterial material = OrionKeyMaterial.open(store, options,
                new OrionEventManager())) {
            store.material = material;
            assertThat(material.inventory(Set.of())).isEmpty();
            ConfigurationSecretEnvelope envelope = material.seal(material.configurationCipherKey(), new byte[]{1}, context);
            assertThat(material.open(material.configurationCipherKey(), envelope, context)).containsExactly((byte) 1);
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.writes).isEqualTo(1);
            material.seal(material.configurationCipherKey(), new byte[]{2}, context);
            assertThat(store.writes).isEqualTo(1);
        }
    }

    @Test
    void persistsConfigurationCipherBeforeReturningAnEnvelopeAndReusesItAfterRestart() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        ConfigurationSecretEnvelope envelope;
        String savedVersion;
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String initialVersion = store.revision().orElseThrow();
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[0], context))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.revision().orElseThrow()).isEqualTo(initialVersion);
            envelope = material.seal(material.configurationCipherKey(), "git-token".getBytes(StandardCharsets.UTF_8), context);
            savedVersion = store.revision().orElseThrow();
            assertThat(savedVersion).isNotEqualTo(initialVersion);
            assertThat(material.open(material.configurationCipherKey(), envelope, context))
                    .isEqualTo("git-token".getBytes(StandardCharsets.UTF_8));
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            assertThat(material.open(material.configurationCipherKey(), envelope, context))
                    .isEqualTo("git-token".getBytes(StandardCharsets.UTF_8));
            material.seal(material.configurationCipherKey(), "another-token".getBytes(StandardCharsets.UTF_8), context);
            assertThat(store.revision().orElseThrow()).isEqualTo(savedVersion);
        }
    }

    @Test
    void doesNotCreateAMissingCipherWhileOpeningAnExistingEnvelope() throws Exception {
        InMemoryKeyMaterialContentStore source = new InMemoryKeyMaterialContentStore();
        InMemoryKeyMaterialContentStore destination = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        ConfigurationSecretEnvelope envelope;
        try (OrionKeyMaterial material = openWithKey(source, signing)) {
            envelope = material.seal(material.configurationCipherKey(), new byte[]{1}, context);
        }
        try (OrionKeyMaterial material = openWithKey(destination, signing)) {
            String initialVersion = destination.revision().orElseThrow();
            assertThatThrownBy(() -> material.open(material.configurationCipherKey(), envelope, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(destination.revision().orElseThrow()).isEqualTo(initialVersion);
        }
    }

    @Test
    void rejectsUseOfAnUncommittedCipherAfterItsMaterialSaveFails() throws Exception {
        class FailingStore extends InMemoryKeyMaterialContentStore {
            private boolean fail;

            @Override
            public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
                if (fail) {
                    throw new IOException("save failed");
                }
                return super.write(bytes, expectedVersion);
            }
        }
        FailingStore store = new FailingStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String before = store.revision().orElseThrow();
            store.fail = true;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(store.revision().orElseThrow()).isEqualTo(before);
            store.fail = false;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{2}, context))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            ConfigurationSecretEnvelope envelope = reopened.seal(reopened.configurationCipherKey(), new byte[]{3}, context);
            assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 3);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesGeneratedKeysAfterFailedSaveAndReopensOnlyDurableMaterial(boolean committed) throws Exception {
        FailingStore store = new FailingStore(committed);
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        TlsMaterialConfiguration configuration = tlsConfiguration();
        KeyMaterialDescriptor account = descriptor(
                "account-v1", KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, 1);
        String afterFailure;
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String before = store.revision().orElseThrow();
            store.fail = true;
            assertThatThrownBy(() -> generateKeys(material, account, configuration.identity()))
                    .isInstanceOf(IOException.class).hasMessage("Material save failed");
            afterFailure = store.revision().orElseThrow();
            assertThat(afterFailure.equals(before)).isEqualTo(!committed);
            store.fail = false;
            assertThatThrownBy(() -> generateKeys(material, account, configuration.identity()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThatThrownBy(() -> material.certificateIdentity(configuration).issuedCertificateChain())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            generateKeys(reopened, account, configuration.identity());
            assertThat(reopened.keyPair(account).getPrivate()).isNotNull();
            assertThat(reopened.keyPair(configuration.identity()).getPrivate()).isNotNull();
            assertThat(store.revision().orElseThrow().equals(afterFailure)).isEqualTo(committed);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCertificateSaveClosesTheOwnerWithoutExposingAnUnsavedChain(boolean committed)
            throws Exception {
        FailingStore store = new FailingStore(committed);
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        TlsMaterialConfiguration configuration = tlsConfiguration();
        KeyMaterialDescriptor account = descriptor(
                "account-v1", KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, 1);
        X509Certificate expected;
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            generateKeys(material, account, configuration.identity());
            TestCertificateChain.Authority issuer = TestCertificateChain.root("Certificate issuer");
            X509Certificate original = TestCertificateChain.leaf(
                    "original.example", material.keyPair(configuration.identity()), issuer);
            X509Certificate replacement = TestCertificateChain.leaf(
                    "replacement.example", material.keyPair(configuration.identity()), issuer);
            material.certificateIdentity(configuration).installCertificateChain(
                    List.of(original), Optional.empty());
            assertThatThrownBy(() -> material.certificateIdentity(configuration)
                    .installCertificateChain(List.of(), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(material.certificateIdentity(configuration).issuedCertificateChain().orElseThrow())
                    .containsExactly(original);
            store.fail = true;
            assertThatThrownBy(() -> material.certificateIdentity(configuration)
                    .installCertificateChain(List.of(replacement), Optional.empty()))
                    .isInstanceOf(IOException.class).hasMessage("Material save failed");
            store.fail = false;
            assertThatThrownBy(() -> material.certificateIdentity(configuration).issuedCertificateChain())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThatThrownBy(() -> material.certificateIdentity(configuration)
                    .installCertificateChain(List.of(replacement), Optional.empty()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            expected = committed ? replacement : original;
        }
        String durableVersion = store.revision().orElseThrow();
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            assertThat(reopened.certificateIdentity(configuration).issuedCertificateChain().orElseThrow())
                    .containsExactly(expected);
            assertThat(store.revision().orElseThrow()).isEqualTo(durableVersion);
        }
    }

    private static TlsMaterialConfiguration tlsConfiguration() {
        return new TlsMaterialConfiguration(
                descriptor("https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1),
                Optional.empty(), List.of(), TlsClientAuthentication.DISABLED);
    }

    private static void generateKeys(OrionKeyMaterial material, KeyMaterialDescriptor first,
            KeyMaterialDescriptor second) throws IOException, GeneralSecurityException {
        material.withModification(modification -> {
            modification.generateKey(first, 2048);
            modification.generateKey(second, 2048);
            modification.apply();
        });
    }

    private static final class FailingStore extends InMemoryKeyMaterialContentStore {
        private final boolean committed;
        private boolean fail;

        private FailingStore(boolean committed) {
            this.committed = committed;
        }

        @Override
        public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
            if (!fail) {
                return super.write(bytes, expectedVersion);
            }
            if (committed) {
                super.write(bytes, expectedVersion);
            }
            throw new IOException("Material save failed");
        }
    }

    private static KeyMaterialDescriptor descriptor(
            String alias,
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm,
            long version) {
        return new KeyMaterialDescriptor(
                alias,
                Set.of(purpose),
                algorithm,
                version);
    }

    @Test
    void doesNotReplaceAnAliasOwnedByAnotherMaterialPurpose() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor signing = rsa("configuration-v1", 1);
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String before = store.revision().orElseThrow();
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1},
                    new ConfigurationSecretContext("system/token", "credential")))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(store.revision().orElseThrow()).isEqualTo(before);
            assertThat(material.sign(signing, new byte[]{1})).isNotEmpty();
        }
    }

    @Test
    void reopensAConcurrentCipherWinnerWithoutOverwritingItsKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (OrionKeyMaterial first = openWithKey(store, signing);
             OrionKeyMaterial second = OrionKeyMaterial.open(store, options(),
                     new OrionEventManager())) {
            ConfigurationSecretEnvelope envelope = first.seal(first.configurationCipherKey(), new byte[]{1}, context);
            String winnerVersion = store.revision().orElseThrow();
            assertThatThrownBy(() -> second.seal(second.configurationCipherKey(), new byte[]{2}, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(store.revision().orElseThrow()).isEqualTo(winnerVersion);
            try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(),
                    new OrionEventManager())) {
                assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 1);
            }
        }
    }

    @Test
    void reusesThePersistedCipherWhenItsFirstSaveResponseWasLost() throws Exception {
        class LostResponseStore extends InMemoryKeyMaterialContentStore {
            private boolean loseResponse;

            @Override
            public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
                String saved = super.write(bytes, expectedVersion);
                if (loseResponse) {
                    throw new IOException("response lost");
                }
                return saved;
            }
        }
        LostResponseStore store = new LostResponseStore();
        KeyMaterialDescriptor signing = rsa("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            store.loseResponse = true;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, context))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        String savedVersion = store.revision().orElseThrow();
        store.loseResponse = false;
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(),
                new OrionEventManager())) {
            ConfigurationSecretEnvelope envelope = reopened.seal(reopened.configurationCipherKey(), new byte[]{2}, context);
            assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 2);
            assertThat(store.revision().orElseThrow()).isEqualTo(savedVersion);
        }
    }

    private static OrionKeyMaterial openWithKey(KeyMaterialContentStore store, KeyMaterialDescriptor key)
            throws Exception {
        OrionKeyMaterial material = OrionKeyMaterial.open(store, options(),
                new OrionEventManager());
        try {
            material.withModification(modification -> {
                modification.generateKey(key, 2048);
                modification.apply();
            });
            return material;
        } catch (Exception failure) {
            material.close();
            throw failure;
        }
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }

    private static final class CountingStore implements KeyMaterialContentStore {
        private final InMemoryKeyMaterialContentStore delegate = new InMemoryKeyMaterialContentStore();
        private int readCalls;

        @Override
        public Optional<String> revision() throws IOException {
            readCalls++;
            return delegate.revision();
        }

        @Override
        public byte[] read(String revision) throws IOException {
            readCalls++;
            return delegate.read(revision);
        }

        @Override
        public String write(byte[] bytes, String expectedVersion) throws IOException {
            return delegate.write(bytes, expectedVersion);
        }
    }
}
