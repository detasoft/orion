package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;

import java.io.IOException;

/**
 * Pack bytes, independent of object lookup and publication. The caller owns each new writable handle,
 * flushes it before publication and closes it after writing. Closing a handle leaves its bytes in storage.
 * Reads borrow a bounded input only for the callback. Missing or truncated bytes fail with IOException.
 * The repository owner closes storage after its users have finished; unpublished bytes may await cleanup.
 */
public interface GitStorageApi extends AutoCloseable {
    PackDataStorage newPack(PackId packId) throws IOException;

    <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException;

    boolean exists(PackId packId) throws IOException;

    @Override
    void close() throws IOException;
}
