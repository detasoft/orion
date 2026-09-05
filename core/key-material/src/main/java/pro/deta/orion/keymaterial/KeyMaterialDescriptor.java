package pro.deta.orion.keymaterial;

public record KeyMaterialDescriptor(
        KeyMaterialAlias alias,
        KeyMaterialPurpose purpose,
        KeyMaterialAlgorithm algorithm,
        KeyMaterialVersion version,
        KeyMaterialScope scope) {
    public KeyMaterialDescriptor {
        if (alias == null) {
            throw new IllegalArgumentException("Key material alias must not be null");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("Key material purpose must not be null");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("Key material algorithm must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("Key material version must not be null");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Key material scope must not be null");
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
