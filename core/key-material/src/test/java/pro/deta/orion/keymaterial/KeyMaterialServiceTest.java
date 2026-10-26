package pro.deta.orion.keymaterial;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void createsPkcs12InMemoryStoreAndReloadsPrivateKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        KeyMaterialService service = KeyMaterialService.open(store, options());
        KeyPair generated = generateKey(service, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);
        String savedVersion = store.revision().orElseThrow();

        KeyMaterialService reloaded = KeyMaterialService.open(store, options());
        KeyPair loaded = reloaded.getKeyPair(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);
        Certificate[] chain = reloaded.getCertificateChain(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);

        assertThat(savedVersion).isNotBlank();
        assertThat(reloaded.containsAlias(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS, Set.of())).isTrue();
        assertThat(loaded.getPublic().getEncoded()).isEqualTo(generated.getPublic().getEncoded());
        assertThat(loaded.getPrivate().getAlgorithm()).isEqualTo(generated.getPrivate().getAlgorithm());
        assertThat(chain).hasSize(1);
        assertThat(chain[0]).isInstanceOf(X509Certificate.class);

        X509Certificate storageCertificate = (X509Certificate) chain[0];
        assertThat(storageCertificate.getBasicConstraints()).isEqualTo(-1);
        assertThat(storageCertificate.getSubjectX500Principal().getName())
                .contains(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);
        storageCertificate.verify(loaded.getPublic());
    }

    @Test
    void localPkcs12StoreCanBeOpenedWithJavaKeyStore() throws Exception {
        Path keyStorePath = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(keyStorePath);

        KeyMaterialService service = KeyMaterialService.open(store, options());
        generateKey(service, descriptor(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS,
                KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA), 2048);

        KeyStore keyStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, KeyMaterialTestConstants.password());
        }

        assertThat(Files.size(keyStorePath)).isGreaterThan(0);
        assertThat(keyStore.containsAlias(KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS)).isTrue();
        assertThat(keyStore.getCertificateChain(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS)).hasSize(1);
    }

    @Test
    void trustedCertificatesRoundTripSeparatelyFromPrivateKeyEntries() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialService service = KeyMaterialService.open(store, options());
        generateKey(service, descriptor(
                KeyMaterialTestConstants.ORION_CA_2026_05_ALIAS,
                KeyMaterialPurpose.CERTIFICATE_AUTHORITY, KeyMaterialAlgorithm.RSA), 2048);
        X509Certificate storageCertificate = (X509Certificate) service.getCertificateChain(
                KeyMaterialTestConstants.ORION_CA_2026_05_ALIAS)[0];
        X509Certificate issuerCertificate = TestCertificateChain.root("Public Root").certificate();
        TrustedCertificateDescriptor descriptor = trustedCertificate(
                KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS,
                1);

        assertThatThrownBy(() -> trustCertificate(service, descriptor, storageCertificate))
                .hasMessageContaining("certificate authority");
        trustCertificate(service, descriptor, issuerCertificate);

        KeyMaterialService reloaded = KeyMaterialService.open(store, options());
        X509Certificate trustedCertificate = reloaded.getTrustedCertificate(descriptor);

        assertThat(trustedCertificate.getEncoded()).isEqualTo(issuerCertificate.getEncoded());
        assertThatThrownBy(() -> reloaded.getPrivateKey(KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Private key alias not found");
    }

    @Test
    void validatesTypedTrustedCertificateMetadataAndEntryType() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        TrustedCertificateDescriptor original = trustedCertificate(
                "public-root-v1", 1);
        KeyMaterialDescriptor privateEntry = new KeyMaterialDescriptor(
                "private-entry-v1",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA,
                1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            trustCertificate(service, original, root.certificate());
            generateKey(service, privateEntry, 2048);
        }

        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            TrustedCertificateDescriptor wrongVersion = trustedCertificate(
                    original.alias(), 2);
            TrustedCertificateDescriptor wrongAlgorithm = new TrustedCertificateDescriptor(
                    original.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                    KeyMaterialAlgorithm.EC,
                    original.version());
            TrustedCertificateDescriptor wrongEntryType = trustedCertificate(
                    privateEntry.alias(), 1);

            assertThatThrownBy(() -> service.validateExisting(wrongVersion))
                    .hasMessageContaining("version");
            assertThatThrownBy(() -> service.validateExisting(wrongAlgorithm))
                    .hasMessageContaining("algorithm");
            assertThatThrownBy(() -> service.validateExisting(wrongEntryType))
                    .hasMessageContaining("entry type");
        }
    }

    @Test
    void discoversTypedPrivateKeysByPurpose() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        KeyMaterialDescriptor rsa = descriptor(
                "ssh-host-z-rsa-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA);
        KeyMaterialDescriptor ec = descriptor(
                "ssh-host-a-ec-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.EC);
        KeyMaterialDescriptor signing = descriptor(
                "server-signing-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA);
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            generateKey(service, rsa, 2048);
            generateKey(service, ec, 256);
            generateKey(service, signing, 2048);
        }

        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            assertThat(service.privateKeyDescriptors(Set.of(KeyMaterialPurpose.SSH_HOST)))
                    .containsExactly(ec, rsa);
        }
    }

    @Test
    void inventoriesPublicMaterialWithoutExposingPrivateOrSecretKeys() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        KeyMaterialDescriptor identity = descriptor(
                "tls-identity-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA);
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1", KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES);
        TrustedCertificateDescriptor trust = trustedCertificate("issuer-v1", 1);
        TestCertificateChain.Authority issuer = TestCertificateChain.root("Public Root");
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            KeyPair keyPair = generateKey(service, identity, 2048);
            KeyMaterialModification modification = service.modify();
            try {
                modification.generateKey(cipher, 256);
                modification.setTrustedCertificate(trust, issuer.certificate());
                modification.installCertificateChain(identity,
                        List.of(TestCertificateChain.leaf("orion.test", keyPair, issuer)), Optional.of(trust));
                modification.apply();
            } finally {
                modification.discard();
            }
        }

        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            List<KeyMaterialInventoryEntry> entries = service.inventory(Set.of());
            assertThat(entries).extracting(KeyMaterialInventoryEntry::alias)
                    .containsExactly("configuration-v1", "issuer-v1", "tls-identity-v1");
            assertThat(entries.get(0).publicKeyPem()).isNull();
            assertThat(entries.get(0).certificates()).isEmpty();
            assertThat(entries.get(1).certificates()).hasSize(1);
            assertThat(entries.get(2).publicKeyPem()).startsWith("-----BEGIN PUBLIC KEY-----");
            assertThat(entries.get(2).certificates()).hasSize(2);
            assertThat(entries.get(2).certificates().getFirst().subject()).contains("orion.test");
            assertThat(entries.get(2).certificates().getFirst().dnsNames()).isEmpty();
            assertThat(entries.toString()).doesNotContain("PRIVATE KEY", "SECRET KEY");
        }
    }

    @Test
    void refusesPrivateKeyWhenCertificateChainHasDifferentPublicKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialService service = KeyMaterialService.open(store, options());

        KeyPair signingKey = generateKey(service, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);
        generateKey(service, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);
        Certificate[] mismatchedChain = service.getCertificateChain(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS);
        KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(
                KeyMaterialTestConstants.BAD_SERVER_SIGNING_ALIAS,
                Set.of(KeyMaterialPurpose.TLS_IDENTITY),
                KeyMaterialAlgorithm.RSA,
                1);

        KeyMaterialModification modification = service.modify();
        try {
            modification.importKey(descriptor, signingKey);
            assertThatThrownBy(() -> modification.installCertificateChain(
                    descriptor, List.of(mismatchedChain), Optional.empty()))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("public key does not match");
        } finally {
            modification.discard();
        }
    }

    @Test
    void detectsConflictingStoreUpdates() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        KeyMaterialService initial = KeyMaterialService.open(store, options());
        generateKey(initial, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);

        KeyMaterialService first = KeyMaterialService.open(store, options());
        KeyMaterialService second = KeyMaterialService.open(store, options());

        generateKey(second, descriptor(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS,
                KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA), 2048);

        KeyMaterialModification modification = first.modify();
        try {
            modification.generateKey(descriptor("acme-account",
                    KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA), 2048);
            assertThatThrownBy(modification::apply)
                    .isInstanceOf(KeyMaterialStoreConflictException.class)
                    .hasMessageContaining("changed before save");
        } finally {
            modification.discard();
        }
    }

    @Test
    void loadsInlinePkcs12BytesFromContentBase64ResourceStore() throws Exception {
        InMemoryKeyMaterialContentStore writableStore = new InMemoryKeyMaterialContentStore();
        KeyMaterialService writable = KeyMaterialService.open(writableStore, options());
        generateKey(writable, descriptor(
                KeyMaterialTestConstants.HTTPS_2026_05_ALIAS,
                KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA), 2048);

        byte[] pkcs12Bytes = writableStore.read(writableStore.revision().orElseThrow());
        String reference = KeyMaterialTestConstants.contentBase64Reference(pkcs12Bytes);
        KeyMaterialContentStore readOnlyStore = KeyMaterialResourceResolver.standard().resolveStore(reference);
        KeyMaterialService readOnly = KeyMaterialService.open(readOnlyStore, options());

        assertThat(readOnly.containsAlias(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS, Set.of())).isTrue();
        assertThat(readOnly.getCertificateChain(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS)).hasSize(1);
    }

    @Test
    void serviceOwnsAndClearsItsPasswordLifecycle() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialOptions callerOptions = options();
        KeyMaterialService service = KeyMaterialService.open(store, callerOptions);

        try {
            callerOptions.close();
            generateKey(service, descriptor(
                    KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                    KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);

            service.close();

            assertThatThrownBy(callerOptions::password)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(() -> service.containsAlias(
                    KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS, Set.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        } finally {
            service.close();
            callerOptions.close();
        }
    }

    @Test
    void corruptExistingStoreIsNeverReplaced() throws Exception {
        Path keyStorePath = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(keyStorePath);
        byte[] corruptBytes = "not-a-pkcs12-store".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String corruptVersion = store.write(corruptBytes, null);

        try (KeyMaterialOptions options = options()) {
            assertThatThrownBy(() -> KeyMaterialService.open(store, options))
                    .isInstanceOf(java.io.IOException.class);
        }

        String afterFailureRevision = store.revision().orElseThrow();

        byte[] afterFailureBytes = store.read(afterFailureRevision);
        assertThat(afterFailureRevision).isEqualTo(corruptVersion);
        assertThat(afterFailureBytes).isEqualTo(corruptBytes);
    }

    private static KeyPair generateKey(KeyMaterialService service, KeyMaterialDescriptor descriptor,
            int keySize) throws Exception {
        KeyMaterialModification modification = service.modify();
        try {
            modification.generateKey(descriptor, keySize);
            modification.apply();
            return service.getKeyPair(descriptor.alias());
        } finally {
            modification.discard();
        }
    }

    private static void trustCertificate(KeyMaterialService service, TrustedCertificateDescriptor descriptor,
            X509Certificate certificate) throws Exception {
        KeyMaterialModification modification = service.modify();
        try {
            modification.setTrustedCertificate(descriptor, certificate);
            modification.apply();
        } finally {
            modification.discard();
        }
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }

    private static TrustedCertificateDescriptor trustedCertificate(
            String alias,
            long version) {
        return new TrustedCertificateDescriptor(
                alias, Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                KeyMaterialAlgorithm.RSA,
                version);
    }

    private static KeyMaterialDescriptor descriptor(
            String alias,
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm) {
        return new KeyMaterialDescriptor(
                alias,
                Set.of(purpose),
                algorithm,
                1);
    }
}
