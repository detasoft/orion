package pro.deta.orion.keymaterial;

import java.util.Arrays;

public record KeyMaterialOptions(String type, char[] password, boolean createIfMissing) {
    public KeyMaterialOptions {
        if (type == null || type.isBlank()) {
            type = KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE;
        }
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Key material password must not be empty");
        }
        password = Arrays.copyOf(password, password.length);
    }

    public static KeyMaterialOptions pkcs12(char[] password, boolean createIfMissing) {
        return new KeyMaterialOptions(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE, password, createIfMissing);
    }

    @Override
    public char[] password() {
        return Arrays.copyOf(password, password.length);
    }
}
