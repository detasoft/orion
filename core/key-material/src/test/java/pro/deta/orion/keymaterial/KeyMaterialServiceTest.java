package pro.deta.orion.keymaterial;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void createsPkcs12InMemoryStoreAndReloadsPrivateKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        KeyMaterialService service = KeyMaterialService.open(store, options(true));
        KeyPair generated = service.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));
        String savedVersion = service.save();

        KeyMaterialService reloaded = KeyMaterialService.open(store, options(false));
        KeyPair loaded = reloaded.getKeyPair(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);
        Certificate[] chain = reloaded.getCertificateChain(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);

        assertThat(savedVersion).isNotBlank();
        assertThat(reloaded.containsAlias(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS)).isTrue();
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

        KeyMaterialService service = KeyMaterialService.open(store, options(true));
        service.generateKeyIfMissing(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SSH_HOST_PURPOSE));
        service.save();

        KeyStore keyStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, KeyMaterialTestConstants.password());
        }

        assertThat(Files.size(keyStorePath)).isGreaterThan(0);
        assertThat(keyStore.containsAlias(KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS)).isTrue();
        assertThat(keyStore.getCertificateChain(KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS)).hasSize(1);
    }

    @Test
    void trustedCertificatesRoundTripSeparatelyFromPrivateKeyEntries() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialService service = KeyMaterialService.open(store, options(true));
        service.generateKeyIfMissing(
                KeyMaterialTestConstants.ORION_CA_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.CA_ISSUER_PURPOSE));
        X509Certificate issuerCertificate =
                (X509Certificate) service.getCertificateChain(KeyMaterialTestConstants.ORION_CA_2026_05_ALIAS)[0];
        service.setTrustedCertificate(KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS, issuerCertificate);
        service.save();

        KeyMaterialService reloaded = KeyMaterialService.open(store, options(false));
        X509Certificate trustedCertificate =
                reloaded.getTrustedCertificate(KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS);

        assertThat(trustedCertificate.getEncoded()).isEqualTo(issuerCertificate.getEncoded());
        assertThatThrownBy(() -> reloaded.getPrivateKey(KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Private key alias not found");
    }

    @Test
    void refusesPrivateKeyWhenCertificateChainHasDifferentPublicKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialService service = KeyMaterialService.open(store, options(true));

        KeyPair signingKey = service.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));
        service.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));
        Certificate[] mismatchedChain = service.getCertificateChain(KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS);

        assertThatThrownBy(() -> service.setPrivateKey(
                KeyMaterialTestConstants.BAD_SERVER_SIGNING_ALIAS,
                signingKey,
                mismatchedChain))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Certificate public key does not match");
    }

    @Test
    void returnsConfiguredSigningKeysAndRotatesActiveAlias() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialService service = KeyMaterialService.open(
                store,
                options(true),
                Map.of(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE, new KeyMaterialSigningKeyConfig(
                        KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                        List.of(KeyMaterialTestConstants.SERVER_SIGNING_2026_04_ALIAS))));
        service.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_04_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));
        KeyPair active = service.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));
        KeyPair next = service.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));

        assertThat(service.getActiveSigningKey(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE).getPublic().getEncoded())
                .isEqualTo(active.getPublic().getEncoded());
        assertThat(service.getVerificationKeys(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE)).hasSize(2);

        KeyMaterialSigningKeyConfig rotated = service.rotate(
                KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE,
                KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS);

        assertThat(rotated.activeAlias()).isEqualTo(KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS);
        assertThat(rotated.verificationAliases()).containsExactly(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialTestConstants.SERVER_SIGNING_2026_04_ALIAS);
        assertThat(service.getActiveSigningKey(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE).getPublic().getEncoded())
                .isEqualTo(next.getPublic().getEncoded());
        assertThat(service.getVerificationKeys(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE)).hasSize(3);
    }

    @Test
    void detectsConflictingStoreUpdates() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        KeyMaterialService initial = KeyMaterialService.open(store, options(true));
        initial.generateKeyIfMissing(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SERVER_SIGNING_PURPOSE));
        initial.save();

        KeyMaterialService first = KeyMaterialService.open(store, options(false));
        KeyMaterialService second = KeyMaterialService.open(store, options(false));

        second.generateKeyIfMissing(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.SSH_HOST_PURPOSE));
        second.save();

        first.generateKeyIfMissing(
                KeyMaterialTestConstants.ACME_ACCOUNT_PURPOSE,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.ACME_ACCOUNT_PURPOSE));
        assertThatThrownBy(first::save)
                .isInstanceOf(KeyMaterialStoreConflictException.class)
                .hasMessageContaining("changed before save");
    }

    @Test
    void refusesMissingStoreWhenCreationIsDisabled() {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        assertThatThrownBy(() -> KeyMaterialService.open(store, options(false)))
                .isInstanceOf(KeyMaterialStoreNotFoundException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void loadsInlinePkcs12BytesFromContentBase64ResourceStore() throws Exception {
        InMemoryKeyMaterialContentStore writableStore = new InMemoryKeyMaterialContentStore();
        KeyMaterialService writable = KeyMaterialService.open(writableStore, options(true));
        writable.generateKeyIfMissing(
                KeyMaterialTestConstants.HTTPS_2026_05_ALIAS,
                KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.HTTPS_PURPOSE));
        writable.save();

        byte[] pkcs12Bytes = writableStore.read().orElseThrow().bytes();
        String reference = KeyMaterialTestConstants.contentBase64Reference(pkcs12Bytes);
        KeyMaterialContentStore readOnlyStore = KeyMaterialResourceResolver.standard().resolveStore(reference);
        KeyMaterialService readOnly = KeyMaterialService.open(readOnlyStore, options(false));

        assertThat(readOnly.containsAlias(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS)).isTrue();
        assertThat(readOnly.getCertificateChain(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS)).hasSize(1);
    }

    private static KeyMaterialOptions options(boolean createIfMissing) {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password(), createIfMissing);
    }
}
