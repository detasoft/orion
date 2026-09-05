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
        if (!(account.scope() instanceof KeyMaterialScope.Cluster)
                || !(identity.scope() instanceof KeyMaterialScope.Cluster)) {
            throw new IllegalArgumentException("ACME material must be cluster-scoped");
        }
        if (!account.scope().equals(identity.scope())) {
            throw new IllegalArgumentException("ACME account and TLS identity must have the same scope");
        }
        issuerTrustAnchor.ifPresent(issuer -> requireSameScope(identity.scope(), issuer.scope()));
    }

    private static void requirePurpose(
            KeyMaterialDescriptor descriptor,
            KeyMaterialPurpose purpose,
            String label) {
        if (descriptor == null || descriptor.purpose() != purpose) {
            throw new IllegalArgumentException(label + " must use " + purpose + " purpose");
        }
    }

    private static void requireSameScope(KeyMaterialScope identity, KeyMaterialScope issuer) {
        if (!identity.equals(issuer)) {
            throw new IllegalArgumentException("ACME issuer trust anchor must have the identity scope");
        }
    }
}
