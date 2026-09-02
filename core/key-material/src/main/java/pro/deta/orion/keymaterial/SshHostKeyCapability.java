package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;

public interface SshHostKeyCapability {
    List<KeyMaterialDescriptor> descriptors();

    List<KeyPair> keyPairs() throws GeneralSecurityException;
}
