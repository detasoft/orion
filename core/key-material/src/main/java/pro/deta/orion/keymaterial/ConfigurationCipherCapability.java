package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

public interface ConfigurationCipherCapability {
    KeyMaterialDescriptor descriptor();

    ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context)
            throws GeneralSecurityException;

    byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context)
            throws GeneralSecurityException;

    static ConfigurationCipherCapability unavailable() {
        return new ConfigurationCipherCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                throw new IllegalStateException("Configuration cipher is not available");
            }

            @Override
            public ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context) {
                throw new IllegalStateException("Configuration cipher is not available");
            }

            @Override
            public byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context) {
                throw new IllegalStateException("Configuration cipher is not available");
            }
        };
    }
}
