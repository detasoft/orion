package pro.deta.orion.keymaterial;

import java.util.Optional;

public record AcmeMaterialConfiguration(
        KeyMaterialDescriptor account,
        KeyMaterialDescriptor identity,
        Optional<TrustedCertificateDescriptor> issuerTrustAnchor) {
    public AcmeMaterialConfiguration {
        requirePurpose(account, KeyMaterialPurpose.ACME_ACCOUNT, "ACME account");
        requirePurpose(identity, KeyMaterialPurpose.TLS_IDENTITY, "ACME TLS identity");
        if (issuerTrustAnchor == null) {
            throw new IllegalArgumentException("ACME issuer trust anchor optional must not be null");
        }
    }

    private static void requirePurpose(
            KeyMaterialDescriptor descriptor,
            KeyMaterialPurpose purpose,
            String label) {
        if (descriptor == null || descriptor.purpose() != purpose) {
            throw new IllegalArgumentException(label + " must use " + purpose + " purpose");
        }
    }

}
