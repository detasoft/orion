package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

public interface CertificateAuthorityCapability {
    KeyMaterialDescriptor descriptor();

    byte[] sign(byte[] payload) throws GeneralSecurityException;

    X509Certificate[] certificateChain() throws GeneralSecurityException;
}
