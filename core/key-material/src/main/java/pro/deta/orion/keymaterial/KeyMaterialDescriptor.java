package pro.deta.orion.keymaterial;

import java.util.Set;

public record KeyMaterialDescriptor(
        String alias,
        Set<KeyMaterialPurpose> purposes,
        KeyMaterialAlgorithm algorithm,
        long version) {
    public KeyMaterialDescriptor {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
        if (purposes == null || purposes.isEmpty()) {
            throw new IllegalArgumentException("Key material purposes must not be empty");
        }
        purposes = Set.copyOf(purposes);
        if (purposes.contains(KeyMaterialPurpose.ACME_ACCOUNT) && purposes.size() != 1) {
            throw new IllegalArgumentException("ACME account keys must have only ACME_ACCOUNT purpose");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("Key material algorithm must not be null");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Key material version must be positive");
        }
        if (purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors require a trusted certificate descriptor");
        }
        if (purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER) && algorithm != KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Configuration cipher material must use AES");
        }
        if (algorithm == KeyMaterialAlgorithm.AES
                && !purposes.equals(Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER))) {
            throw new IllegalArgumentException("AES material is only valid for configuration ciphers");
        }
    }
}
