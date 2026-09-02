package pro.deta.orion.keymaterial;

import java.util.Arrays;

public record ConfigurationSecretEnvelope(
        int version,
        KeyMaterialAlias keyAlias,
        KeyMaterialVersion keyVersion,
        String wrappingAlgorithm,
        String encryptionAlgorithm,
        String encoding,
        byte[] wrappedDataKey,
        byte[] nonce,
        byte[] ciphertext) {
    public ConfigurationSecretEnvelope {
        if (version < 1) {
            throw new IllegalArgumentException("Envelope version must be positive");
        }
        if (keyAlias == null) {
            throw new IllegalArgumentException("Envelope key alias must not be null");
        }
        if (keyVersion == null) {
            throw new IllegalArgumentException("Envelope key version must not be null");
        }
        requireText(wrappingAlgorithm, "Envelope wrapping algorithm");
        requireText(encryptionAlgorithm, "Envelope encryption algorithm");
        requireText(encoding, "Envelope encoding");
        wrappedDataKey = copyRequired(wrappedDataKey, "Envelope wrapped data key");
        nonce = copyRequired(nonce, "Envelope nonce");
        ciphertext = copyRequired(ciphertext, "Envelope ciphertext");
    }

    @Override
    public byte[] wrappedDataKey() {
        return Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private static byte[] copyRequired(byte[] value, String label) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return Arrays.copyOf(value, value.length);
    }
}
