package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;

import java.io.IOException;

/**
 * Operation-scoped access to pack bytes, independent of object lookup and publication. Callers flush
 * writable handles before publication and close them after writing. Closing an access closes its remaining
 * writable handles; neither close removes stored bytes. Reads borrow a bounded input only for the callback.
 * Missing or truncated bytes fail with IOException. Unpublished bytes may await repository cleanup.
 */
public interface GitStorageAccess extends AutoCloseable {
    PackHandle newPack(PackId packId) throws IOException;

    <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException;

    boolean exists(PackId packId) throws IOException;

    @Override
    void close() throws IOException;
}
