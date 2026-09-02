package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.util.List;

public interface VerificationCapability {
    List<KeyMaterialDescriptor> descriptors();

    boolean verify(byte[] payload, byte[] signature) throws GeneralSecurityException;
}
