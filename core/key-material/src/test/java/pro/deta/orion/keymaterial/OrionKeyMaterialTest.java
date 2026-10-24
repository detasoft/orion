package pro.deta.orion.keymaterial;

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
    void createsActiveIdentityOnlyForANewStoreAndReloadsIt() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;

        assertThatThrownBy(() -> OrionKeyMaterial.open(store, options(), signing, 2048))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("creation was not requested");
        assertThat(store.read()).isEmpty();

        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            SigningIdentity identity = material.signingIdentity();
            signature = identity.sign(payload);
            assertThat(identity.activeKeyId()).isEqualTo("server-signing-v1");
            assertThat(identity.verify("server-signing-v1", payload, signature)).isTrue();
            assertThat(identity.publicKeys()).hasSize(1);
        }

        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            SigningIdentity identity = material.signingIdentity();
            assertThat(identity.verify("server-signing-v1", payload, signature)).isTrue();
            assertThat(identity.verify("unknown", payload, signature)).isFalse();
        }
    }

    @Test
    void retainsExactOlderAliasForVerificationAcrossReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor old = rsa("server-signing-v1", 1);
        KeyMaterialDescriptor active = rsa("server-signing-v2", 2);
        byte[] payload = "retained-jwt".getBytes(StandardCharsets.UTF_8);
        byte[] oldSignature;
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            service.generateKeyIfMissing(old, 2048);
            service.generateKeyIfMissing(active, 2048);
            oldSignature = KeyMaterialCapabilities.open(service, List.of(old, active))
                    .signing(old)
                    .sign(payload);
            service.save();
        }

        SigningMaterialSet signing = new SigningMaterialSet(active, List.of(old));
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            SigningIdentity identity = material.signingIdentity();
            byte[] activeSignature = identity.sign(payload);

            assertThat(identity.activeKeyId()).isEqualTo("server-signing-v2");
            assertThat(identity.verify("server-signing-v1", payload, oldSignature)).isTrue();
            assertThat(identity.verify("server-signing-v2", payload, activeSignature)).isTrue();
            assertThat(identity.verify("server-signing-v1", payload, activeSignature)).isFalse();
            assertThat(identity.publicKeys()).hasSize(1);
            assertThat(identity.retainedPublicKeys()).hasSize(1);
        }
    }

    @Test
    void stagesActivatesAndRetiresSigningAliasWithoutDeletingStoredKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor old = rsa("server-signing-v1", 1);
        KeyMaterialDescriptor next = rsa("server-signing-v2", 2);
        byte[] payload = "rotation-payload".getBytes(StandardCharsets.UTF_8);
        byte[] oldSignature;
        byte[] nextSignature;
        SigningMaterialSet initial = new SigningMaterialSet(old, List.of());

        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), initial, 2048,
                new KeyMaterialCreation(true))) {
            oldSignature = material.signingIdentity().sign(payload);
        }
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            service.generateKeyIfMissing(next, 2048);
            service.save();
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), initial, 2048)) {
            assertThat(material.signingIdentity().activeKeyId()).isEqualTo(old.alias());
            assertThat(material.signingIdentity().hasVerificationKey(next.alias())).isFalse();
        }

        SigningMaterialSet retained = new SigningMaterialSet(next, List.of(old));
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), retained, 2048)) {
            SigningIdentity identity = material.signingIdentity();
            nextSignature = identity.sign(payload);
            assertThat(identity.activeKeyId()).isEqualTo(next.alias());
            assertThat(identity.verify(old.alias(), payload, oldSignature)).isTrue();
            assertThat(identity.verify(next.alias(), payload, nextSignature)).isTrue();
        }

        SigningMaterialSet retired = new SigningMaterialSet(next, List.of());
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), retired, 2048)) {
            SigningIdentity identity = material.signingIdentity();
            assertThat(identity.verify(old.alias(), payload, oldSignature)).isFalse();
            assertThat(identity.verify(next.alias(), payload, nextSignature)).isTrue();
            assertThat(identity.retainedPublicKeys()).isEmpty();
        }
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            assertThat(service.containsAlias(old.alias(), Set.of())).isTrue();
            assertThat(service.getKeyPair(old.alias()).getPublic().getEncoded()).isNotEmpty();
            assertThat(service.containsAlias(next.alias(), Set.of())).isTrue();
        }
    }

    @Test
    void doesNotGenerateMissingIdentityIntoAnExistingStore() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor existing = rsa("other-signing", 1);
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            service.generateKeyIfMissing(existing, 2048);
            service.save();
        }
        String before = store.read().orElseThrow().version();
        SigningMaterialSet missing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());

        assertThatThrownBy(() -> OrionKeyMaterial.open(store, options(), missing, 2048))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("server-signing-v1");
        assertThat(store.read().orElseThrow().version()).isEqualTo(before);
    }

    @Test
    void rejectsNonRsaServerIdentityBeforeSigning() {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor ed25519 = descriptor(
                "server-signing-ed25519",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.ED25519,
                1);

        assertThatThrownBy(() -> OrionKeyMaterial.open(
                store,
                options(),
                new SigningMaterialSet(ed25519, List.of()),
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RSA");
    }

    @Test
    void sharesInstalledCertificateIdentityWithTls() throws Exception {
        CountingStore store = new CountingStore();
        OrionKeyMaterial material = OrionKeyMaterial.open(
                store,
                options(),
                new SigningMaterialSet(rsa("server-signing-v1", 1), List.of()),
                2048,
                new KeyMaterialCreation(true));
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor identity = descriptor(
                "https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1);
        TlsMaterialConfiguration tls = new TlsMaterialConfiguration(
                identity, Optional.empty(), List.of(), TlsClientAuthentication.DISABLED);

        generateKeys(material, account, identity);
        CertificateIdentity certificate = material.certificateIdentity(tls);
        assertThatThrownBy(certificate::createContext)
                .hasMessageContaining("issued certificate chain");
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        material.certificateIdentity(tls).installCertificateChain(
                List.of(TestCertificateChain.leaf("orion.example", material.keyPair(identity), intermediate)),
                Optional.empty());

        assertThat(material.signingIdentity().activeKeyId()).isEqualTo("server-signing-v1");
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
        try (KeyMaterialOptions options = options(); OrionKeyMaterial material = OrionKeyMaterial.open(store, options)) {
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
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        ConfigurationSecretEnvelope envelope;
        String savedVersion;
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            String initialVersion = store.read().orElseThrow().version();
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[0], context))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.read().orElseThrow().version()).isEqualTo(initialVersion);
            envelope = material.seal(material.configurationCipherKey(), "git-token".getBytes(StandardCharsets.UTF_8), context);
            savedVersion = store.read().orElseThrow().version();
            assertThat(savedVersion).isNotEqualTo(initialVersion);
            assertThat(material.open(material.configurationCipherKey(), envelope, context))
                    .isEqualTo("git-token".getBytes(StandardCharsets.UTF_8));
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            assertThat(material.open(material.configurationCipherKey(), envelope, context))
                    .isEqualTo("git-token".getBytes(StandardCharsets.UTF_8));
            material.seal(material.configurationCipherKey(), "another-token".getBytes(StandardCharsets.UTF_8), context);
            assertThat(store.read().orElseThrow().version()).isEqualTo(savedVersion);
        }
    }

    @Test
    void doesNotCreateAMissingCipherWhileOpeningAnExistingEnvelope() throws Exception {
        InMemoryKeyMaterialContentStore source = new InMemoryKeyMaterialContentStore();
        InMemoryKeyMaterialContentStore destination = new InMemoryKeyMaterialContentStore();
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        ConfigurationSecretEnvelope envelope;
        try (OrionKeyMaterial material = OrionKeyMaterial.open(source, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            envelope = material.seal(material.configurationCipherKey(), new byte[]{1}, context);
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(destination, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            String initialVersion = destination.read().orElseThrow().version();
            assertThatThrownBy(() -> material.open(material.configurationCipherKey(), envelope, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(destination.read().orElseThrow().version()).isEqualTo(initialVersion);
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
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            String before = store.read().orElseThrow().version();
            store.fail = true;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(store.read().orElseThrow().version()).isEqualTo(before);
            store.fail = false;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{2}, context))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            ConfigurationSecretEnvelope envelope = reopened.seal(reopened.configurationCipherKey(), new byte[]{3}, context);
            assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 3);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesGeneratedKeysAfterFailedSaveAndReopensOnlyDurableMaterial(boolean committed) throws Exception {
        FailingStore store = new FailingStore(committed);
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        TlsMaterialConfiguration configuration = tlsConfiguration();
        KeyMaterialDescriptor account = descriptor(
                "account-v1", KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, 1);
        String afterFailure;
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            String before = store.read().orElseThrow().version();
            store.fail = true;
            assertThatThrownBy(() -> generateKeys(material, account, configuration.identity()))
                    .isInstanceOf(IOException.class).hasMessage("Material save failed");
            afterFailure = store.read().orElseThrow().version();
            assertThat(afterFailure.equals(before)).isEqualTo(!committed);
            store.fail = false;
            assertThatThrownBy(() -> generateKeys(material, account, configuration.identity()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThatThrownBy(() -> material.certificateIdentity(configuration).issuedCertificateChain())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            generateKeys(reopened, account, configuration.identity());
            assertThat(reopened.keyPair(account).getPrivate()).isNotNull();
            assertThat(reopened.keyPair(configuration.identity()).getPrivate()).isNotNull();
            assertThat(store.read().orElseThrow().version().equals(afterFailure)).isEqualTo(committed);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCertificateSaveClosesTheOwnerWithoutExposingAnUnsavedChain(boolean committed)
            throws Exception {
        FailingStore store = new FailingStore(committed);
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        TlsMaterialConfiguration configuration = tlsConfiguration();
        KeyMaterialDescriptor account = descriptor(
                "account-v1", KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, 1);
        X509Certificate expected;
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
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
        String durableVersion = store.read().orElseThrow().version();
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            assertThat(reopened.certificateIdentity(configuration).issuedCertificateChain().orElseThrow())
                    .containsExactly(expected);
            assertThat(store.read().orElseThrow().version()).isEqualTo(durableVersion);
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
        SigningMaterialSet signing = new SigningMaterialSet(rsa("configuration-v1", 1), List.of());
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            String before = store.read().orElseThrow().version();
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1},
                    new ConfigurationSecretContext("system/token", "credential")))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(store.read().orElseThrow().version()).isEqualTo(before);
            assertThat(material.signingIdentity().sign(new byte[]{1})).isNotEmpty();
        }
    }

    @Test
    void reopensAConcurrentCipherWinnerWithoutOverwritingItsKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (OrionKeyMaterial first = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true));
             OrionKeyMaterial second = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            ConfigurationSecretEnvelope envelope = first.seal(first.configurationCipherKey(), new byte[]{1}, context);
            String winnerVersion = store.read().orElseThrow().version();
            assertThatThrownBy(() -> second.seal(second.configurationCipherKey(), new byte[]{2}, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(store.read().orElseThrow().version()).isEqualTo(winnerVersion);
            try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(), signing, 2048)) {
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
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048,
                new KeyMaterialCreation(true))) {
            store.loseResponse = true;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, context))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        String savedVersion = store.read().orElseThrow().version();
        store.loseResponse = false;
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            ConfigurationSecretEnvelope envelope = reopened.seal(reopened.configurationCipherKey(), new byte[]{2}, context);
            assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 2);
            assertThat(store.read().orElseThrow().version()).isEqualTo(savedVersion);
        }
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }

    private static final class CountingStore implements KeyMaterialContentStore {
        private final InMemoryKeyMaterialContentStore delegate = new InMemoryKeyMaterialContentStore();
        private int readCalls;

        @Override
        public Optional<KeyMaterialSnapshot> read() throws IOException {
            readCalls++;
            return delegate.read();
        }

        @Override
        public String write(byte[] bytes, String expectedVersion) throws IOException {
            return delegate.write(bytes, expectedVersion);
        }
    }
}
