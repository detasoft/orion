package pro.deta.orion.keymaterial;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Selected certificate identity shared by issuance and TLS, with its issuer and client trust settings. */
public final class CertificateIdentity {
    private final OrionKeyMaterial material;
    private final TlsMaterialConfiguration configuration;

    CertificateIdentity(OrionKeyMaterial material, TlsMaterialConfiguration configuration) {
        this.material = material;
        this.configuration = configuration;
    }

    public List<X509Certificate> certificateChain() throws GeneralSecurityException {
        return material.certificateChain(configuration.identity());
    }

    public Optional<List<X509Certificate>> issuedCertificateChain() throws GeneralSecurityException {
        return material.issuedCertificateChain(configuration.identity());
    }

    public Optional<X509Certificate> issuerTrustAnchor() throws GeneralSecurityException {
        if (configuration.serverIssuerTrustAnchor().isEmpty()) return Optional.empty();
        return Optional.of(material.trustedCertificate(configuration.serverIssuerTrustAnchor().orElseThrow()));
    }

    public TlsClientAuthentication clientAuthentication() {
        return configuration.clientAuthentication();
    }

    public void installCertificateChain(List<? extends Certificate> certificateChain,
            Optional<X509Certificate> issuerTrustAnchor) throws IOException, GeneralSecurityException {
        List<X509Certificate> validated = requireX509Chain(certificateChain);
        Optional<X509Certificate> root = requireIssuerCertificate(
                configuration.serverIssuerTrustAnchor(), issuerTrustAnchor);
        material.withModification(modification -> {
            if (root.isPresent()) {
                modification.setTrustedCertificate(
                        configuration.serverIssuerTrustAnchor().orElseThrow(), root.orElseThrow());
            }
            modification.installCertificateChain(
                    configuration.identity(), validated, configuration.serverIssuerTrustAnchor());
            modification.apply();
        });
    }

    public SSLContext createContext() throws GeneralSecurityException {
        return material.createTlsContext(configuration);
    }

    static boolean isStorageCertificate(X509Certificate certificate) {
        return certificate.getBasicConstraints() < 0
                && certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                && certificate.getSubjectX500Principal().getName()
                        .contains("O=" + KeyMaterialConstants.STORAGE_CERTIFICATE_ORGANIZATION);
    }

    static List<X509Certificate> requireX509Chain(
            List<? extends Certificate> certificateChain) throws GeneralSecurityException {
        if (certificateChain == null || certificateChain.isEmpty()) {
            throw new IllegalArgumentException("Certificate chain must not be empty");
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : certificateChain) {
            if (!(certificate instanceof X509Certificate x509Certificate)) {
                throw new GeneralSecurityException("Certificate chain contains a non-X.509 certificate");
            }
            certificates.add(x509Certificate);
        }
        return List.copyOf(certificates);
    }

    static Optional<X509Certificate> requireIssuerCertificate(
            Optional<TrustedCertificateDescriptor> descriptor,
            Optional<X509Certificate> certificate) throws GeneralSecurityException {
        if (certificate == null) {
            throw new IllegalArgumentException("Issuer trust anchor optional must not be null");
        }
        if (descriptor.isPresent() != certificate.isPresent()) {
            throw new IllegalArgumentException(
                    "Issuer trust anchor descriptor and certificate must be configured together");
        }
        if (descriptor.isPresent()
                && !descriptor.orElseThrow().algorithm().acceptsKeyAlgorithm(
                        certificate.orElseThrow().getPublicKey().getAlgorithm())) {
            throw new GeneralSecurityException("Issuer trust anchor algorithm does not match its descriptor");
        }
        return certificate;
    }

    static void validateCertificateChain(
            List<X509Certificate> chain,
            java.security.PublicKey identityPublicKey,
            Optional<X509Certificate> root) throws GeneralSecurityException {
        X509Certificate leaf = chain.getFirst();
        if (!publicKeysMatch(identityPublicKey, leaf.getPublicKey())) {
            throw new GeneralSecurityException("Certificate leaf public key does not match TLS identity");
        }
        for (X509Certificate certificate : chain) {
            certificate.checkValidity();
        }
        if (chain.size() > 1 && isSelfSigned(chain.getLast())) {
            throw new GeneralSecurityException(
                    "TLS identity certificate chain must not include the issuer root");
        }
        for (int index = 1; index < chain.size(); index++) {
            if (chain.get(index).getBasicConstraints() < 0) {
                throw new GeneralSecurityException("TLS identity certificate chain contains a non-CA issuer");
            }
        }
        for (int index = 0; index + 1 < chain.size(); index++) {
            verifyCertificate(chain.get(index), chain.get(index + 1), "certificate chain signature");
        }
        if (root.isPresent()) {
            X509Certificate issuerRoot = root.orElseThrow();
            issuerRoot.checkValidity();
            if (issuerRoot.getBasicConstraints() < 0) {
                throw new GeneralSecurityException("Configured issuer root is not a certificate authority");
            }
            verifyCertificate(chain.getLast(), issuerRoot, "issuer root");
            verifyCertificate(issuerRoot, issuerRoot, "issuer root self-signature");
        }
    }

    private static void verifyCertificate(
            X509Certificate certificate,
            X509Certificate issuer,
            String label) throws GeneralSecurityException {
        try {
            certificate.verify(issuer.getPublicKey());
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new GeneralSecurityException("Invalid " + label, failure);
        }
    }

    static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException | RuntimeException failure) {
            return false;
        }
    }

    private static boolean publicKeysMatch(
            java.security.PublicKey expected,
            java.security.PublicKey actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return expected.getAlgorithm().equalsIgnoreCase(actual.getAlgorithm())
                && Arrays.equals(expected.getEncoded(), actual.getEncoded());
    }

}
