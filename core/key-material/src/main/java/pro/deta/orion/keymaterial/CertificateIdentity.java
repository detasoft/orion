package pro.deta.orion.keymaterial;

import java.util.Set;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/** Selected certificate identity shared by issuance and TLS, with its issuer and client trust settings. */
public final class CertificateIdentity {
    private final KeyMaterialService owner;
    private final KeyMaterialCapabilities selected;
    private final KeyMaterialDescriptor identity;
    private final Optional<TrustedCertificateDescriptor> issuer;
    private final List<TrustedCertificateDescriptor> clientTrust;
    private final TlsClientAuthentication clientAuthentication;

    CertificateIdentity(KeyMaterialService owner, KeyMaterialCapabilities selected,
            KeyMaterialDescriptor identity, Optional<TrustedCertificateDescriptor> issuer,
            List<TrustedCertificateDescriptor> clientTrust, TlsClientAuthentication clientAuthentication) {
        this.owner = owner;
        this.selected = selected;
        this.identity = identity;
        this.issuer = issuer;
        this.clientTrust = List.copyOf(clientTrust);
        this.clientAuthentication = clientAuthentication;
    }

    public List<X509Certificate> certificateChain() throws GeneralSecurityException {
        return selected.x509CertificateChain(identity);
    }

    public Optional<List<X509Certificate>> issuedCertificateChain() throws GeneralSecurityException {
        if (!owner.containsAlias(identity.alias(), Set.of())) return Optional.empty();
        List<X509Certificate> chain = certificateChain();
        if (chain.size() == 1 && isStorageCertificate(chain.getFirst())) return Optional.empty();
        return Optional.of(chain);
    }

    public Optional<X509Certificate> issuerTrustAnchor() throws GeneralSecurityException {
        return selected.trustedCertificate(issuer);
    }

    public TlsClientAuthentication clientAuthentication() {
        return clientAuthentication;
    }

    public void installCertificateChain(List<? extends Certificate> certificateChain,
            Optional<X509Certificate> issuerTrustAnchor) throws IOException, GeneralSecurityException {
        List<X509Certificate> validated = KeyMaterialCapabilities.requireX509Chain(certificateChain);
        Optional<X509Certificate> root = KeyMaterialCapabilities.requireIssuerCertificate(issuer, issuerTrustAnchor);
        KeyMaterialCapabilities.validateCertificateChain(validated,
                owner.getKeyPair(identity.alias()).getPublic(), root);
        owner.installCertificateChain(identity, validated, issuer, root);
    }

    public SSLContext createContext() throws GeneralSecurityException {
        List<X509Certificate> chain = certificateChain();
        if (chain.size() == 1 && isStorageCertificate(chain.getFirst())) {
            throw new GeneralSecurityException("TLS identity must contain an issued certificate chain");
        }
        KeyMaterialCapabilities.validateCertificateChain(chain,
                owner.getKeyPair(identity.alias()).getPublic(), issuerTrustAnchor());
        return selected.createTlsContext(identity, chain, clientTrust);
    }

    static boolean isStorageCertificate(X509Certificate certificate) {
        return certificate.getBasicConstraints() < 0
                && certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                && certificate.getSubjectX500Principal().getName()
                        .contains("O=" + KeyMaterialConstants.STORAGE_CERTIFICATE_ORGANIZATION);
    }
}
