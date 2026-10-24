package pro.deta.orion.keymaterial;

public record KeyMaterialDescriptor(
        String alias,
        KeyMaterialPurpose purpose,
        KeyMaterialAlgorithm algorithm,
        long version) {
    public KeyMaterialDescriptor {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("Key material purpose must not be null");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("Key material algorithm must not be null");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Key material version must be positive");
        }
        if (purpose == KeyMaterialPurpose.TRUST_ANCHOR) {
            throw new IllegalArgumentException("Trust anchors require a trusted certificate descriptor");
        }
        if (purpose == KeyMaterialPurpose.CONFIGURATION_CIPHER && algorithm != KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Configuration cipher material must use AES");
        }
        if (purpose != KeyMaterialPurpose.CONFIGURATION_CIPHER && algorithm == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("AES material is only valid for configuration ciphers");
        }
    }
}
