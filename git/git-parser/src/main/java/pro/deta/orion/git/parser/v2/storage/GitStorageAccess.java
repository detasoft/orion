package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.api.Modification;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;

import java.io.IOException;
import java.util.Set;

/**
 * Operation-scoped access to pack bytes, independent of object lookup and publication. Apply closes
 * writable handles and retains packs created by this access; discard closes them and deletes those packs.
 * Finish storage before publishing its index. Reads borrow a bounded input only for the callback.
 * Missing or truncated bytes fail with IOException.
 */
public interface GitStorageAccess extends Modification {
    PackHandle newPack(PackId packId) throws IOException;

    <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException;

    boolean exists(PackId packId) throws IOException;

    /** Snapshot of stored pack IDs; callers must also inspect published and active index accesses. */
    Set<PackId> packIds() throws IOException;
}
