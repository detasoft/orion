package pro.deta.orion.keymaterial;

public record KeyMaterialKeySpec(String algorithm, int keySize, String purpose) {
    public KeyMaterialKeySpec {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("Key algorithm must not be empty");
        }
        if (keySize < 0) {
            throw new IllegalArgumentException("Key size must not be negative");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Key purpose must not be empty");
        }
    }

    public static KeyMaterialKeySpec rsa(String purpose) {
        return new KeyMaterialKeySpec(
                KeyMaterialConstants.RSA_ALGORITHM,
                KeyMaterialConstants.RSA_KEY_SIZE_BITS,
                purpose);
    }

    public static KeyMaterialKeySpec ec(String purpose) {
        return new KeyMaterialKeySpec(
                KeyMaterialConstants.EC_ALGORITHM,
                KeyMaterialConstants.EC_KEY_SIZE_BITS,
                purpose);
    }
}
