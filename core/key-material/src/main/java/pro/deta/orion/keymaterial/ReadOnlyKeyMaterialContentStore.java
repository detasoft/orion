package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

public class ReadOnlyKeyMaterialContentStore implements KeyMaterialContentStore {
    private final byte[] bytes;
    private final String sourceName;
    private final String version;

    public ReadOnlyKeyMaterialContentStore(byte[] bytes, String sourceName) {
        if (bytes == null) {
            throw new IllegalArgumentException("Key material bytes must not be null");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Key material source name must not be empty");
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.sourceName = sourceName;
        this.version = KeyMaterialVersions.sha256(bytes);
    }

    @Override
    public Optional<KeyMaterialSnapshot> read() {
        return Optional.of(new KeyMaterialSnapshot(bytes, version));
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        throw new IOException("Key material store is read-only: " + sourceName);
    }
}
