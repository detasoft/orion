package pro.deta.orion.keymaterial;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

public interface TlsCapability {
    List<X509Certificate> certificateChain(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException;

    Optional<X509Certificate> serverIssuerTrustAnchor(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException;

    SSLContext createContext(TlsMaterialConfiguration configuration) throws GeneralSecurityException;

    static TlsCapability unavailable() {
        return new TlsCapability() {
            @Override
            public List<X509Certificate> certificateChain(TlsMaterialConfiguration configuration) {
                throw unavailableFailure();
            }

            @Override
            public Optional<X509Certificate> serverIssuerTrustAnchor(
                    TlsMaterialConfiguration configuration) {
                throw unavailableFailure();
            }

            @Override
            public SSLContext createContext(TlsMaterialConfiguration configuration) {
                throw unavailableFailure();
            }

            private IllegalStateException unavailableFailure() {
                return new IllegalStateException("TLS key material is not available");
            }
        };
    }
}
