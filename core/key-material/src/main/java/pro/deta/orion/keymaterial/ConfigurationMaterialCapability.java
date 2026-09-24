package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

/** Read-only checks for material referenced by a configuration snapshot. */
public interface ConfigurationMaterialCapability {
    void require(KeyMaterialDescriptor descriptor) throws GeneralSecurityException;

    void require(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException;

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
