package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.util.List;

/** Read-only access to configured and stored key material. */
public interface ConfigurationMaterialCapability {
    void require(KeyMaterialDescriptor descriptor) throws GeneralSecurityException;

    void require(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException;

    default List<KeyMaterialInventoryEntry> inventory() throws GeneralSecurityException {
        throw new GeneralSecurityException("Configuration material inventory is unavailable");
    }

    static ConfigurationMaterialCapability unavailable() {
        return new ConfigurationMaterialCapability() {
            @Override
            public void require(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
                throw new GeneralSecurityException(
                        "Configuration material is unavailable: " + descriptor.alias().value());
            }

            @Override
            public void require(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException {
                throw new GeneralSecurityException(
                        "Configuration material is unavailable: " + descriptor.alias().value());
            }

        };
    }
}
