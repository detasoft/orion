package pro.deta.orion.keymaterial;

import pro.deta.orion.keymaterial.KeyMaterialTestRuntime.TestBlobStorage;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.read;
import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.revision;

import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.InMemoryBlobStorage;

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
    @org.junit.jupiter.api.extension.RegisterExtension
    final KeyMaterialTestRuntime runtime = new KeyMaterialTestRuntime();

    @Test
    void opensAnEmptyStoreWithoutCreatingKeysAndPersistsAnExplicitModification() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            assertThat(material.inventory()).isEmpty();
            assertThat(material.revision()).isEqualTo("1");
            assertThat(read(store)).isNotEmpty();
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey((("server-signing-v1")), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048);
                assertThat(material.contains(signing)).isFalse();
                assertThat(read(store)).isNotEmpty();
                modification.commit(VcChangeDescription.EMPTY);
            }
            signature = material.sign(signing, payload);
            assertThat(material.revision()).isEqualTo("2");
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            assertThat(material.verify(signing, payload, signature)).isTrue();
        }
    }

    @Test
    void retainsSigningKeysAcrossReload() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId old = new KeyMaterialId("server-signing-v1", 1);
        KeyMaterialId active = new KeyMaterialId("server-signing-v2", 1);
        byte[] payload = "retained-jwt".getBytes(StandardCharsets.UTF_8);
        byte[] oldSignature;
        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            KeyMaterialOperation modification = service.open(new VcRevision.Stored(service.revision()));
            try {
                modification.generateKey((("server-signing-v1")), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048);
                modification.generateKey((("server-signing-v2")), Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048);
                modification.commit(VcChangeDescription.EMPTY);
            } finally {
                modification.close();
            }
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            oldSignature = material.sign(old, payload);
        }

        try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            byte[] activeSignature = material.sign(active, payload);

            assertThat(material.verify(old, payload, oldSignature)).isTrue();
            assertThat(material.verify(active, payload, activeSignature)).isTrue();
            assertThat(material.verify(old, payload, activeSignature)).isFalse();
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            assertThat(material.verify(old, payload, oldSignature)).isTrue();
        }
    }

    @Test
    void sharesInstalledCertificateIdentityWithTls() throws Exception {
        CountingStore store = new CountingStore();
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        OrionKeyMaterial material = openWithKey(store, signing);
        KeyMaterialId account = new KeyMaterialId("acme-account-v1", 1);
        KeyMaterialId identity = new KeyMaterialId("https-v1", 1);
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
        CertificateIdentity installed = material.certificateIdentity(tls).installCertificateChain(
                List.of(TestCertificateChain.leaf("orion.example",
                        material.read(identity).keyPair(), intermediate)),
                Optional.empty());

        int sourceReadsAfterSave = store.readCalls;
        assertThat(material.verify(signing, payload, signature)).isTrue();
        assertThat(installed.issuedCertificateChain().orElseThrow()).hasSize(1);
        assertThat(installed.createContext().getProtocol()).isEqualTo("TLS");
        assertThatThrownBy(certificate::createContext).hasMessageContaining("issued certificate chain");
        assertThat(store.readCalls).isEqualTo(sourceReadsAfterSave);
        KeyMaterialId staleAccount = new KeyMaterialId(account.alias(), 2);
        assertThatThrownBy(() -> material.read(staleAccount).keyPair())
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("version");

        material.close();
        material.close();
        assertThatThrownBy(certificate::createContext)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> generateKeys(material, account, identity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }



    @Test
    void publishesTheLazyCipherKeyOnlyAfterTheStoreAcceptsIt() throws Exception {
        class ObservingStore extends TestBlobStorage {
        ObservingStore() { super(runtime.events); }

            private OrionKeyMaterial material;
            private int writes;

            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                try {
                    assertThat(material.contains(material.configurationCipherKey())).isFalse();
                } catch (GeneralSecurityException failure) {
                    throw new IOException(failure);
                }
                writes++;
                return super.commitSnapshot(operation, description);
            }
        }
        ObservingStore store = new ObservingStore();
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (KeyMaterialOptions options = options(); OrionKeyMaterial material = OrionKeyMaterial.open(
                new KeyMaterialRepository(
                        store,
                        new KeyMaterialCodec(options),
                        runtime.events))) {
            store.material = material;
            assertThat(material.inventory()).isEmpty();
            ConfigurationSecretEnvelope envelope = material.seal(material.configurationCipherKey(), new byte[]{1}, context);
            assertThat(material.open(material.configurationCipherKey(), envelope, context)).containsExactly((byte) 1);
            assertThat(material.revision()).isEqualTo("2");
            assertThat(store.writes).isEqualTo(1);
            material.seal(material.configurationCipherKey(), new byte[]{2}, context);
            assertThat(store.writes).isEqualTo(1);
        }
    }

    @Test
    void persistsConfigurationCipherBeforeReturningAnEnvelopeAndReusesItAfterRestart() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        ConfigurationSecretEnvelope envelope;
        String savedVersion;
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String initialVersion = revision(store);
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[0], context))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(revision(store)).isEqualTo(initialVersion);
            envelope = material.seal(material.configurationCipherKey(), "git-token".getBytes(StandardCharsets.UTF_8), context);
            savedVersion = revision(store);
            assertThat(savedVersion).isNotEqualTo(initialVersion);
            assertThat(material.open(material.configurationCipherKey(), envelope, context))
                    .isEqualTo("git-token".getBytes(StandardCharsets.UTF_8));
        }
        try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            assertThat(material.open(material.configurationCipherKey(), envelope, context))
                    .isEqualTo("git-token".getBytes(StandardCharsets.UTF_8));
            material.seal(material.configurationCipherKey(), "another-token".getBytes(StandardCharsets.UTF_8), context);
            assertThat(revision(store)).isEqualTo(savedVersion);
        }
    }

    @Test
    void doesNotCreateAMissingCipherWhileOpeningAnExistingEnvelope() throws Exception {
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events);
        InMemoryBlobStorage destination = new TestBlobStorage(runtime.events);
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        ConfigurationSecretEnvelope envelope;
        try (OrionKeyMaterial material = openWithKey(source, signing)) {
            envelope = material.seal(material.configurationCipherKey(), new byte[]{1}, context);
        }
        try (OrionKeyMaterial material = openWithKey(destination, signing)) {
            String initialVersion = revision(destination);
            assertThatThrownBy(() -> material.open(material.configurationCipherKey(), envelope, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(revision(destination)).isEqualTo(initialVersion);
        }
    }

    @Test
    void rejectsUseOfAnUncommittedCipherAfterItsMaterialSaveFails() throws Exception {
        class FailingStore extends TestBlobStorage {
        FailingStore() { super(runtime.events); }

            private boolean fail;

            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                if (fail) {
                    throw new IOException("save failed");
                }
                return super.commitSnapshot(operation, description);
            }
        }
        FailingStore store = new FailingStore();
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/proxy-token", "credential");
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String before = revision(store);
            store.fail = true;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(revision(store)).isEqualTo(before);
            store.fail = false;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{2}, context))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            ConfigurationSecretEnvelope envelope = reopened.seal(reopened.configurationCipherKey(), new byte[]{3}, context);
            assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 3);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesGeneratedKeysAfterFailedSaveAndReopensOnlyDurableMaterial(boolean committed) throws Exception {
        FailingStore store = new FailingStore(committed);
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        TlsMaterialConfiguration configuration = tlsConfiguration();
        KeyMaterialId account = new KeyMaterialId("account-v1", 1);
        String afterFailure;
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String before = revision(store);
            store.fail = true;
            assertThatThrownBy(() -> generateKeys(material, account, configuration.identity()))
                    .isInstanceOf(java.io.UncheckedIOException.class).hasRootCauseMessage("Material save failed");
            afterFailure = revision(store);
            assertThat(afterFailure.equals(before)).isEqualTo(!committed);
            store.fail = false;
            assertThatThrownBy(() -> generateKeys(material, account, configuration.identity()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThatThrownBy(() -> material.certificateIdentity(configuration).issuedCertificateChain())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            generateKeys(reopened, account, configuration.identity());
            assertThat(reopened.read(account).keyPair().getPrivate()).isNotNull();
            assertThat(reopened.read(configuration.identity()).keyPair().getPrivate()).isNotNull();
            assertThat(revision(store).equals(afterFailure)).isEqualTo(committed);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCertificateSaveClosesTheOwnerWithoutExposingAnUnsavedChain(boolean committed)
            throws Exception {
        FailingStore store = new FailingStore(committed);
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        TlsMaterialConfiguration configuration = tlsConfiguration();
        KeyMaterialId account = new KeyMaterialId("account-v1", 1);
        X509Certificate expected;
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            generateKeys(material, account, configuration.identity());
            TestCertificateChain.Authority issuer = TestCertificateChain.root("Certificate issuer");
            X509Certificate original = TestCertificateChain.leaf(
                    "original.example", material.read(configuration.identity()).keyPair(), issuer);
            X509Certificate replacement = TestCertificateChain.leaf(
                    "replacement.example", material.read(configuration.identity()).keyPair(), issuer);
            CertificateIdentity installed = material.certificateIdentity(configuration).installCertificateChain(
                    List.of(original), Optional.empty());
            assertThatThrownBy(() -> installed.installCertificateChain(List.of(), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(installed.issuedCertificateChain().orElseThrow())
                    .containsExactly(original);
            store.fail = true;
            assertThatThrownBy(() -> installed.installCertificateChain(List.of(replacement), Optional.empty()))
                    .isInstanceOf(java.io.UncheckedIOException.class).hasRootCauseMessage("Material save failed");
            store.fail = false;
            assertThatThrownBy(() -> installed.issuedCertificateChain())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThatThrownBy(() -> installed.installCertificateChain(List.of(replacement), Optional.empty()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            expected = committed ? replacement : original;
        }
        String durableVersion = revision(store);
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            KeyMaterialId identity = configuration.identity();
            TlsMaterialConfiguration saved =
                    new TlsMaterialConfiguration(new KeyMaterialId(identity.alias(), committed ? 3 : 2),
                    configuration.serverIssuerTrustAnchor(), configuration.clientTrustAnchors(),
                    configuration.clientAuthentication());
            assertThat(reopened.certificateIdentity(saved).issuedCertificateChain().orElseThrow())
                    .containsExactly(expected);
            assertThat(revision(store)).isEqualTo(durableVersion);
        }
    }

    private static TlsMaterialConfiguration tlsConfiguration() {
        return new TlsMaterialConfiguration(
                new KeyMaterialId("https-v1", 1),
                Optional.empty(), List.of(), TlsClientAuthentication.DISABLED);
    }

    private static void generateKeys(OrionKeyMaterial material, KeyMaterialId first,
            KeyMaterialId second) throws IOException, GeneralSecurityException {
        try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
            modification.generateKey(first.alias(), Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 2048);
            modification.generateKey(second.alias(), Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 2048);
            modification.commit(VcChangeDescription.EMPTY);
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
            if (!fail) {
                return super.commitSnapshot(operation, description);
            }
            if (committed) {
                super.commitSnapshot(operation, description);
            }
            throw new IOException("Material save failed");
        }
    }



    @Test
    void doesNotReplaceAnAliasOwnedByAnotherMaterialPurpose() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId signing = new KeyMaterialId("configuration-v1", 1);
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            String before = revision(store);
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1},
                    new ConfigurationSecretContext("system/token", "credential")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(revision(store)).isEqualTo(before);
            assertThat(material.sign(signing, new byte[]{1})).isNotEmpty();
        }
    }

    @Test
    void reopensAConcurrentCipherWinnerWithoutOverwritingItsKey() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (OrionKeyMaterial first = openWithKey(store, signing);
             OrionKeyMaterial second = OrionKeyMaterial.open(new KeyMaterialRepository(
                     store,
                     new KeyMaterialCodec(options()),
                     runtime.events))) {
            ConfigurationSecretEnvelope envelope = first.seal(first.configurationCipherKey(), new byte[]{1}, context);
            String winnerVersion = revision(store);
            assertThatThrownBy(() -> second.seal(second.configurationCipherKey(), new byte[]{2}, context))
                    .isInstanceOf(GeneralSecurityException.class);
            assertThat(revision(store)).isEqualTo(winnerVersion);
            try (OrionKeyMaterial reopened = OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options()),
                    runtime.events))) {
                assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 1);
            }
        }
    }

    @Test
    void reusesThePersistedCipherWhenItsFirstSaveResponseWasLost() throws Exception {
        class LostResponseStore extends TestBlobStorage {
        LostResponseStore() { super(runtime.events); }

            private boolean loseResponse;

            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                VcRevision.Stored saved = super.commitSnapshot(operation, description);
                if (loseResponse) {
                    throw new IOException("response lost");
                }
                return saved;
            }
        }
        LostResponseStore store = new LostResponseStore();
        KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("system/token", "credential");
        try (OrionKeyMaterial material = openWithKey(store, signing)) {
            store.loseResponse = true;
            assertThatThrownBy(() -> material.seal(material.configurationCipherKey(), new byte[]{1}, context))
                    .isInstanceOf(GeneralSecurityException.class);
        }
        String savedVersion = revision(store);
        store.loseResponse = false;
        try (OrionKeyMaterial reopened = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events))) {
            ConfigurationSecretEnvelope envelope = reopened.seal(reopened.configurationCipherKey(), new byte[]{2}, context);
            assertThat(reopened.open(reopened.configurationCipherKey(), envelope, context)).containsExactly((byte) 2);
            assertThat(revision(store)).isEqualTo(savedVersion);
        }
    }

    private OrionKeyMaterial openWithKey(BlobStorage store, KeyMaterialId key)
            throws Exception {
        OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                runtime.events));
        try {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(key.alias(), Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            return material;
        } catch (Exception failure) {
            material.close();
            throw failure;
        }
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }

    private final class CountingStore extends TestBlobStorage {
        private int readCalls;

        CountingStore() { super(runtime.events); }

        @Override
        protected byte[] readSnapshot(BlobStorageOperations operation) {
            readCalls++;
            return operation.readContent();
        }
    }
}
