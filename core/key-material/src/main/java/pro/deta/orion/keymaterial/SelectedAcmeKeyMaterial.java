package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

interface SelectedAcmeKeyMaterial {
    AcmeKeyMaterial acquire(int accountKeySize, int domainKeySize)
            throws IOException, GeneralSecurityException;

    void installCertificateChain(
            List<? extends Certificate> certificateChain,
            Optional<X509Certificate> issuerTrustAnchor) throws IOException, GeneralSecurityException;

    List<X509Certificate> certificateChain() throws GeneralSecurityException;

    Optional<X509Certificate> issuerTrustAnchor() throws GeneralSecurityException;
}
