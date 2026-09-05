package pro.deta.orion.keymaterial;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

interface SelectedTlsMaterial {
    List<X509Certificate> certificateChain() throws GeneralSecurityException;

    Optional<X509Certificate> serverIssuerTrustAnchor() throws GeneralSecurityException;

    TlsClientAuthentication clientAuthentication();

    SSLContext createContext() throws GeneralSecurityException;
}
