package pro.deta.orion.keymaterial;

public record TrustedCertificateDescriptor(
        KeyMaterialAlias alias,
        KeyMaterialAlgorithm algorithm,
        KeyMaterialVersion version,
        KeyMaterialScope scope) {
    public TrustedCertificateDescriptor {
        if (alias == null) {
            throw new IllegalArgumentException("Trusted certificate alias must not be null");
        }
        if (algorithm == null || algorithm == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Trusted certificate algorithm must be asymmetric");
        }
        if (version == null) {
            throw new IllegalArgumentException("Trusted certificate version must not be null");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Trusted certificate scope must not be null");
        }
    }

    public KeyMaterialPurpose purpose() {
        return KeyMaterialPurpose.TRUST_ANCHOR;
    }
}
