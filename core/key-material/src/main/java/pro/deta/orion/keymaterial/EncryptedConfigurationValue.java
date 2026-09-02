package pro.deta.orion.keymaterial;

import java.util.Arrays;

public record EncryptedConfigurationValue(byte[] nonce, byte[] ciphertext) {
    public EncryptedConfigurationValue {
        if (nonce == null || nonce.length == 0) {
            throw new IllegalArgumentException("Encryption nonce must not be empty");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("Encrypted configuration value must not be empty");
        }
        nonce = Arrays.copyOf(nonce, nonce.length);
        ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }
}
