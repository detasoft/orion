package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Optional;

public interface KeyMaterialContentStore {
    Optional<KeyMaterialSnapshot> read() throws IOException;

    String write(byte[] bytes, String expectedVersion) throws IOException;
}
