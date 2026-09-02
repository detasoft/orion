package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

public interface ConfigurationCipherCapability {
    KeyMaterialDescriptor descriptor();

    ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context)
            throws GeneralSecurityException;

    byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context)
            throws GeneralSecurityException;
}
