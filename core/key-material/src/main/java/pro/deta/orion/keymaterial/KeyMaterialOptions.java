package pro.deta.orion.keymaterial;

import java.util.Arrays;

public final class KeyMaterialOptions implements AutoCloseable {
    private final String type;
    private final char[] password;
    private boolean closed;

    public KeyMaterialOptions(String type, char[] password) {
        this.type = type == null || type.isBlank() ? KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE : type;
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Key material password must not be empty");
        }
        this.password = Arrays.copyOf(password, password.length);
    }

    public static KeyMaterialOptions pkcs12(char[] password) {
        return new KeyMaterialOptions(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE, password);
    }

    public String type() {
        return type;
    }

    public synchronized char[] password() {
        requireOpen();
        return Arrays.copyOf(password, password.length);
    }

    synchronized KeyMaterialOptions copy() {
        requireOpen();
        return new KeyMaterialOptions(type, password);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        Arrays.fill(password, '\0');
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Key material options are closed");
        }
    }
}
