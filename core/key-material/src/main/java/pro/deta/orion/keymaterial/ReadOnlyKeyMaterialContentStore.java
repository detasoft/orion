package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Optional;

public class ReadOnlyKeyMaterialContentStore implements KeyMaterialContentStore {
    private final KeyMaterialSnapshot snapshot;
    private final String sourceName;

    public ReadOnlyKeyMaterialContentStore(byte[] bytes, String sourceName) {
        if (bytes == null) {
            throw new IllegalArgumentException("Key material bytes must not be null");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Key material source name must not be empty");
        }
        this.snapshot = new KeyMaterialSnapshot(bytes, KeyMaterialVersions.sha256(bytes));
        this.sourceName = sourceName;
    }

    @Override
    public Optional<KeyMaterialSnapshot> read() {
        return Optional.of(snapshot);
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        throw new IOException("Key material store is read-only: " + sourceName);
    }
}
