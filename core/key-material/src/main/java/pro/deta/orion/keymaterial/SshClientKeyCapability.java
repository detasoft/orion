package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.security.KeyPair;

public interface SshClientKeyCapability {
    KeyMaterialDescriptor descriptor();

    KeyPair keyPair() throws GeneralSecurityException;
}
