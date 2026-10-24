package pro.deta.orion.keymaterial;

public record TrustedCertificateDescriptor(
        String alias,
        KeyMaterialAlgorithm algorithm,
        long version) {
    public TrustedCertificateDescriptor {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Trusted certificate alias must not be empty");
        }
        if (algorithm == null || algorithm == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Trusted certificate algorithm must be asymmetric");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Trusted certificate version must be positive");
        }
    }

    public KeyMaterialPurpose purpose() {
        return KeyMaterialPurpose.TRUST_ANCHOR;
    }
}
