package pro.deta.orion.keymaterial;

import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

/** One exact stored material version and its validated metadata and Java content. */
public record KeyMaterialEntry(KeyMaterialId id, Set<KeyMaterialPurpose> purposes,
        KeyMaterialAlgorithm algorithm, KeyStore.Entry content) {
    public KeyMaterialEntry {
        Objects.requireNonNull(id, "id");
        validateMetadata(purposes, algorithm);
        purposes = Set.copyOf(purposes);
        Objects.requireNonNull(content, "content");
    }

    static void validateMetadata(Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm) {
        if (purposes == null || purposes.isEmpty()) {
            throw new IllegalArgumentException("Key material purposes must not be empty");
        }
        if (algorithm == null) throw new IllegalArgumentException("Key material algorithm must not be null");
        if (purposes.contains(KeyMaterialPurpose.ACME_ACCOUNT) && purposes.size() != 1) {
            throw new IllegalArgumentException("ACME account keys must have only ACME_ACCOUNT purpose");
        }
        if (purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER) && algorithm != KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Configuration cipher material must use AES");
        }
        if (algorithm == KeyMaterialAlgorithm.AES
                && !purposes.equals(Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER))) {
            throw new IllegalArgumentException("AES material is only valid for configuration ciphers");
        }
    }

    public KeyPair keyPair() throws GeneralSecurityException {
        if (!(content instanceof KeyStore.PrivateKeyEntry key)) {
            throw new GeneralSecurityException("Private key not found: " + id);
        }
        return new KeyPair(key.getCertificate().getPublicKey(), key.getPrivateKey());
    }

    public PublicKey publicKey() throws GeneralSecurityException {
        if (content instanceof KeyStore.TrustedCertificateEntry trusted) {
            return trusted.getTrustedCertificate().getPublicKey();
        }
        return keyPair().getPublic();
    }

    public List<X509Certificate> certificateChain() throws GeneralSecurityException {
        if (!(content instanceof KeyStore.PrivateKeyEntry key)) {
            throw new GeneralSecurityException("Certificate chain not found: " + id);
        }
        List<X509Certificate> chain = CertificateIdentity.requireX509Chain(
                Arrays.asList(key.getCertificateChain()));
        if (chain.size() > 1 && CertificateIdentity.isSelfSigned(chain.getLast())) {
            return List.copyOf(chain.subList(0, chain.size() - 1));
        }
        return chain;
    }

    public Optional<List<X509Certificate>> issuedCertificateChain() throws GeneralSecurityException {
        List<X509Certificate> chain = certificateChain();
        if (chain.size() == 1 && CertificateIdentity.isStorageCertificate(chain.getFirst()))
                return Optional.empty();
        return Optional.of(chain);
    }

    public X509Certificate trustedCertificate() throws GeneralSecurityException {
        if (!(content instanceof KeyStore.TrustedCertificateEntry trusted)
                || !(trusted.getTrustedCertificate() instanceof X509Certificate certificate)) {
            throw new GeneralSecurityException("Trusted certificate not found: " + id);
        }
        return certificate;
    }
}
