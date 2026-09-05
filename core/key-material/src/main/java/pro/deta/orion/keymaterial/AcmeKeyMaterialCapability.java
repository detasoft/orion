package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

public interface AcmeKeyMaterialCapability {
    AcmeKeyMaterial acquire(
            AcmeMaterialConfiguration configuration,
            int accountKeySize,
            int domainKeySize)
            throws IOException, GeneralSecurityException;

    void installCertificateChain(
            AcmeMaterialConfiguration configuration,
            List<? extends Certificate> certificateChain,
            Optional<X509Certificate> issuerTrustAnchor) throws IOException, GeneralSecurityException;

    Optional<List<X509Certificate>> certificateChain(AcmeMaterialConfiguration configuration)
            throws GeneralSecurityException;

    Optional<X509Certificate> issuerTrustAnchor(AcmeMaterialConfiguration configuration)
            throws GeneralSecurityException;

    static AcmeKeyMaterialCapability unavailable() {
        return new AcmeKeyMaterialCapability() {
            @Override
            public AcmeKeyMaterial acquire(
                    AcmeMaterialConfiguration configuration,
                    int accountKeySize,
                    int domainKeySize) {
                throw unavailableFailure();
            }

            @Override
            public void installCertificateChain(
                    AcmeMaterialConfiguration configuration,
                    List<? extends Certificate> certificateChain,
                    Optional<X509Certificate> issuerTrustAnchor) {
                throw unavailableFailure();
            }

            @Override
            public Optional<List<X509Certificate>> certificateChain(AcmeMaterialConfiguration configuration) {
                throw unavailableFailure();
            }

            @Override
            public Optional<X509Certificate> issuerTrustAnchor(AcmeMaterialConfiguration configuration) {
                throw unavailableFailure();
            }

            private IllegalStateException unavailableFailure() {
                return new IllegalStateException("ACME key material is not available");
            }
        };
    }
}
