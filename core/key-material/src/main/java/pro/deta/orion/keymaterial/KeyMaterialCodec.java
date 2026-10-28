package pro.deta.orion.keymaterial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;

/** Converts key stores to and from bytes while owning the format options and clearing temporary buffers. */
public final class KeyMaterialCodec implements AutoCloseable {
    private final KeyMaterialOptions options;

    public KeyMaterialCodec(KeyMaterialOptions options) {
        this.options = Objects.requireNonNull(options, "options").copy();
    }

    KeyStore create() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(options.type());
        char[] password = options.password();
        try {
            keyStore.load(null, password);
            return keyStore;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    KeyStore decode(byte[] content)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(content, "content");
        KeyStore keyStore = KeyStore.getInstance(options.type());
        char[] password = options.password();
        try {
            keyStore.load(new ByteArrayInputStream(content), password);
            return keyStore;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    byte[] encode(KeyStore keyStore)
            throws IOException, GeneralSecurityException {
        char[] password = options.password();
        try (SensitiveByteArrayOutputStream output = new SensitiveByteArrayOutputStream()) {
            keyStore.store(output, password);
            return output.toByteArray();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    char[] password() {
        return options.password();
    }

    @Override
    public void close() {
        options.close();
    }

    private static final class SensitiveByteArrayOutputStream extends ByteArrayOutputStream {
        @Override
        public void close() throws IOException {
            Arrays.fill(buf, (byte) 0);
            super.close();
        }
    }
}
