package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public class ReadOnlyKeyMaterialContentStore implements KeyMaterialContentStore {
    private final byte[] bytes;
    private final String sourceName;

    public ReadOnlyKeyMaterialContentStore(byte[] bytes, String sourceName) {
        if (bytes == null) {
            throw new IllegalArgumentException("Key material bytes must not be null");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Key material source name must not be empty");
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.sourceName = sourceName;
    }

    @Override
    public Optional<String> revision() {
        return Optional.of(KeyMaterialVersions.sha256(bytes));
    }

    @Override
    public byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        if (!revision.equals(KeyMaterialVersions.sha256(bytes))) {
            throw new KeyMaterialStoreConflictException("Key material revision is no longer available: " + revision);
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        throw new IOException("Key material store is read-only: " + sourceName);
    }
}
