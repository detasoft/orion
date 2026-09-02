package pro.deta.orion.keymaterial;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public record ConfigurationSecretContext(String secretId, String kind) {
    private static final byte[] FORMAT = "orion-configuration-secret-context"
            .getBytes(StandardCharsets.UTF_8);
    private static final int FORMAT_VERSION = 1;

    public ConfigurationSecretContext {
        requireRequiredIdentifier(secretId, "Secret ID");
        requireRequiredIdentifier(kind, "Secret kind");
    }

    public byte[] authenticatedBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeBytes(output, FORMAT);
            output.writeInt(FORMAT_VERSION);
            writeBytes(output, secretId.getBytes(StandardCharsets.UTF_8));
            writeBytes(output, kind.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode configuration secret context", e);
        }
        return bytes.toByteArray();
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static void requireRequiredIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
