package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

public interface ConfigurationCipherCapability {
    KeyMaterialDescriptor descriptor();

    EncryptedConfigurationValue encrypt(byte[] plaintext) throws GeneralSecurityException;

    byte[] decrypt(EncryptedConfigurationValue encrypted) throws GeneralSecurityException;
}
