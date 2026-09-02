package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

public interface SigningCapability {
    KeyMaterialDescriptor descriptor();

    byte[] sign(byte[] payload) throws GeneralSecurityException;
}
