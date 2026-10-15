package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

/** Encryption operations bound to one configuration key; its owner creates and persists that key on first seal. */
public class ConfigurationSecretCipher {
    private final KeyMaterialDescriptor descriptor;
    private final KeyMaterialCapabilities selected;
    private final OrionKeyMaterial owner;

    protected ConfigurationSecretCipher() {
        descriptor = null;
        selected = null;
        owner = null;
    }

    ConfigurationSecretCipher(KeyMaterialDescriptor descriptor, KeyMaterialCapabilities selected) {
        this.descriptor = descriptor;
        this.selected = selected;
        this.owner = null;
    }

    ConfigurationSecretCipher(KeyMaterialDescriptor descriptor, OrionKeyMaterial owner) {
        this.descriptor = descriptor;
        this.selected = null;
        this.owner = owner;
    }

    public static ConfigurationSecretCipher unavailable() {
        return new ConfigurationSecretCipher();
    }

    public KeyMaterialDescriptor descriptor() {
        if (descriptor == null) throw new IllegalStateException("Configuration cipher is not available");
        return descriptor;
    }

    public ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context)
            throws GeneralSecurityException {
        if (descriptor == null) throw new IllegalStateException("Configuration cipher is not available");
        return owner == null ? selected.sealConfigurationSecret(descriptor, plaintext, context)
                : owner.sealConfigurationSecret(descriptor, plaintext, context);
    }

    public byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context)
            throws GeneralSecurityException {
        if (descriptor == null) throw new IllegalStateException("Configuration cipher is not available");
        return owner == null ? selected.openConfigurationSecret(descriptor, envelope, context)
                : owner.openConfigurationSecret(descriptor, envelope, context);
    }
}
