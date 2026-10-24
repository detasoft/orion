package pro.deta.orion.keymaterial;

import java.util.Set;

public record TrustedCertificateDescriptor(
        String alias,
        Set<KeyMaterialPurpose> purposes,
        KeyMaterialAlgorithm algorithm,
        long version) {
    public TrustedCertificateDescriptor {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Trusted certificate alias must not be empty");
        }
        if (purposes == null || !purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trusted certificates require TRUST_ANCHOR purpose");
        }
        purposes = Set.copyOf(purposes);
        if (purposes.contains(KeyMaterialPurpose.ACME_ACCOUNT)
                || purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            throw new IllegalArgumentException("Trusted certificates cannot be account or symmetric key material");
        }
        if (algorithm == null || algorithm == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Trusted certificate algorithm must be asymmetric");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Trusted certificate version must be positive");
        }
    }
}
