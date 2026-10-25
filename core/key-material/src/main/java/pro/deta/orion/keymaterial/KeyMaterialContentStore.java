package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Optional;
import java.util.Objects;

public interface KeyMaterialContentStore extends AutoCloseable {
    Optional<KeyMaterialSnapshot> read() throws IOException;

    default Optional<KeyMaterialSnapshot> read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        Optional<KeyMaterialSnapshot> snapshot = read();
        if (snapshot.isEmpty() || !revision.equals(snapshot.orElseThrow().version())) {
            throw new KeyMaterialStoreConflictException("Key material revision is no longer available: " + revision);
        }
        return snapshot;
    }

    String write(byte[] bytes, String expectedVersion) throws IOException;

    @Override
    default void close() {}
}
