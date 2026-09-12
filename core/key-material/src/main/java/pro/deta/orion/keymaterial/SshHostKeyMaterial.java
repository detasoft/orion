package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

/**
 * Typed storage operations used by the SSH transport to own its host-key lifecycle policy.
 */
public interface SshHostKeyMaterial {
    KeyMaterialScope.Cluster scope();

    List<KeyMaterialDescriptor> available() throws GeneralSecurityException;

    void generateIfMissing(Map<KeyMaterialDescriptor, Integer> keySizes)
            throws IOException, GeneralSecurityException;

    SshHostKeyCapability load(List<KeyMaterialDescriptor> descriptors)
            throws GeneralSecurityException;
}
