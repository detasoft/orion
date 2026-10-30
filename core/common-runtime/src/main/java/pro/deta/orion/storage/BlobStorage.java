package pro.deta.orion.storage;

import java.io.IOException;
import java.util.function.Consumer;
import pro.deta.orion.util.Subscription;

/**
 * Loads bytes with their exact source revision. A null revision denotes an absent source; null bytes
 * denote absent content, including at an existing Git revision. Returned buffers belong to the caller.
 * Pinned reads report missing content at an existing revision with FileNotFoundException.
 * A modification publishes bytes only against its expected revision and returns the committed revision.
 * Write buffers remain owned by the caller; implementations must not retain them.
 */
public interface BlobStorage extends AutoCloseable {
    record Content(String revision, byte[] bytes) {}

    record Modification(String expectedRevision, byte[] bytes) {}

    Content load() throws IOException;

    byte[] read(String revision) throws IOException;

    String write(Modification modification) throws IOException;

    Subscription subscribe(Consumer<BlobChanged> subscriber);

    @Override
    default void close() {}
}
