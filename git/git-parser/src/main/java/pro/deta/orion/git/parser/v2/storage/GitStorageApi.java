package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitPackRead;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Repository object storage, independent of the backing store.
 * Working packs come from newPack(); callers complete their index before persist().
 * Persist accepts completed mutable working packs from the same implementation and consumes accepted
 * packs on success or failure.
 * An incompatible pack is rejected without taking ownership. After successful publication callers must
 * not use the pack; the storage owns any resources retained for reads until it closes.
 * findPacksByObjectIds scans published per-pack indexes and groups containing pack IDs by object ID;
 * one object may occur in several packs, and IDs absent from published indexes are omitted.
 * Index read failures are reported as errors, never as absent objects. No shared lookup index is required.
 */
public interface GitStorageApi extends AutoCloseable {
    MutableIndexedPack newPack() throws IOException;

    PackChecksum persist(MutableIndexedPack pack) throws IOException;

    <R> Optional<R> readObject(ObjectId objectId, GitObjectRead<R> reader) throws IOException;

    List<PackObjectLocation> locateObjects(Collection<ObjectId> objectIds) throws IOException;

    <R> R readObject(PackObjectLocation location, GitObjectRead<R> reader) throws IOException;

    Set<ObjectId> packObjectIds(PackChecksum id) throws IOException;

    boolean exists(ObjectId objectId) throws IOException;

    Map<ObjectId, List<PackChecksum>> findPacksByObjectIds(Collection<ObjectId> objectIds) throws IOException;

    List<PackChecksum> packIds() throws IOException;

    <R> Optional<R> readPack(PackChecksum id, GitPackRead<R> reader) throws IOException;

    void close() throws IOException;
}
