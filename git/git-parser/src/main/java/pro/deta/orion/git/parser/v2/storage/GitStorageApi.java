package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.data.ObjectRead;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.data.PackRead;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides the single external API for storage operations belonging to one repository.
 * Commands use this facade; ref, pack, and object stores remain internal implementation details.
 * Object resolution, operation-specific validation, access checks, and upstream forwarding belong to callers.
 *
 * <p>Only published packs contribute objects to readObject, findPacksByObjectIds, and publishedPacks.
 * openPack reads original quarantined or published bytes without changing publication state.
 * ObjectRead and PackRead handles belong to the caller; absence is Optional.empty(), while I/O failures
 * remain errors. Open PackRead handles retain their bytes until closed.
 * Published objects and external bases must remain readable after ingestion closes. Dependencies are stored
 * as externalBaseIds; storage locates their backing objects without persisted externalPackIds.
 * Ref-update failures do not undo pack publication.
 * addObjectEntry registers an already verified, durably stored loose object by ID, logical type, and size;
 * it does not receive bytes and preserves other locations of that object.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code publishPack(packId)} - publish a prepared pack and its dependencies.</li>
 *   <li>{@code addObjectEntry(objectId, type, size)} - register an already stored loose object.</li>
 *   <li>{@code openPack(packId)} - open original pack bytes as a caller-owned PackRead.</li>
 *   <li>{@code snapshotRefs()} - return refs and symbolic or detached HEAD from one consistent state.</li>
 *   <li>{@code updateRefs(updates, atomic)} - conditionally update refs and return one RefUpdateResult
 *       containing the original update per input, in request order.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 *   <li>{@code findPacksByObjectIds(objectIds)} - find published packs containing requested objects.</li>
 *   <li>{@code readObject(objectId)} - open resolved object content as a caller-owned ObjectRead.</li>
 *   <li>{@code readObjectPrefix(objectId, maxDataBytes)} - return type, size, and a bounded prefix.</li>
 * </ul>
 * Methods remain placeholders. Object resolution and operation-specific policy belong to the caller;
 * all operations address the same repository, and ref targets must be available before updates become visible.
 */
public final class GitStorageApi {
    public Optional<PackRead> openPack(PackId packId) throws IOException {
        throw new UnsupportedOperationException("Pack reads are not implemented");
    }

    public void addObjectEntry(ObjectId objectId, ObjectType type, long size) throws IOException {
        throw new UnsupportedOperationException("Loose object registration is not implemented");
    }

    public void publishPack(PackId packId) throws IOException {
        throw new UnsupportedOperationException("Pack publication is not implemented");
    }

    public Optional<ObjectRead> readObject(ObjectId objectId) throws IOException {
        throw new UnsupportedOperationException("Object reads are not implemented");
    }

    public RefsSnapshot snapshotRefs() {
        throw new UnsupportedOperationException("Ref snapshots are not implemented");
    }

    public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
        throw new UnsupportedOperationException("Ref updates are not implemented");
    }

    /**
     * Planned lookup of published packs containing each requested object, including external delta bases.
     * Returns object IDs mapped to lists of containing pack IDs; an object can occur in multiple packs.
     * Objects absent from published packs are omitted, but may still exist as loose objects.
     * Index read failures must be reported as errors, not treated as absent objects. Results are derived
     * from storage and do not introduce persisted externalPackIds in pack manifests.
     *
     * @param objectIds object IDs to locate, not pack IDs
     * @return containing pack IDs grouped by object ID
     * @throws UnsupportedOperationException until the lookup is implemented
     */
    public Map<ObjectId, List<PackId>> findPacksByObjectIds(Collection<ObjectId> objectIds) {
        throw new UnsupportedOperationException("Pack lookup by object IDs is not implemented");
    }
}
