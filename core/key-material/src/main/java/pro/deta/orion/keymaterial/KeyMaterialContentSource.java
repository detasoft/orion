package pro.deta.orion.keymaterial;

import java.io.IOException;

/**
 * Loads bytes with their exact source revision. A null revision denotes an absent source; null bytes
 * denote absent content, including at an existing Git revision. Returned buffers belong to the caller.
 * Pinned reads report missing content at an existing revision with FileNotFoundException.
 */
public interface KeyMaterialContentSource extends AutoCloseable {
    record Content(String revision, byte[] bytes) {}

    Content load() throws IOException;

    byte[] read(String revision) throws IOException;

    String write(byte[] bytes, String expectedRevision) throws IOException;

    @Override
    default void close() {}
}
