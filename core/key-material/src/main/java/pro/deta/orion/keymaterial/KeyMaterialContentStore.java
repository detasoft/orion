package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Optional;

public interface KeyMaterialContentStore extends AutoCloseable {
    Optional<String> revision() throws IOException;

    byte[] read(String revision) throws IOException;

    String write(byte[] bytes, String expectedRevision) throws IOException;

    @Override
    default void close() {}
}
