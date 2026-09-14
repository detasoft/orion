package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Provides the single external API for storage operations belonging to one repository.
 * Commands use this API directly; ref, pack, and object stores remain internal implementation details.
 * Receive validation, access checks, and upstream forwarding belong to the operations using this API.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code snapshotRefs()} - return refs and symbolic or detached HEAD from one consistent state.</li>
 *   <li>{@code updateRefs(List<RefUpdate> updates, boolean atomic)} - conditionally update refs
 *       and return one RefUpdateResult containing the original update per input, in request order.</li>
 *   <li>{@code publishPack(receivedPack)} - retain a validated pack and make its objects readable.</li>
 *   <li>{@code publishedPacks()} - list the metadata of published packs.</li>
 *   <li>{@code openPublishedPack(packId)} - open a published pack for reading.</li>
 *   <li>{@code findPacksByObjectIds(objectIds)} - find published packs containing the requested objects.</li>
 *   <li>{@code readObject(objectId)} - return the object's type and full content, or absence.</li>
 *   <li>{@code readObjectPrefix(objectId, maxDataBytes)} - return type, full size, and a bounded prefix.</li>
 * </ul>
 * Method names and signatures are provisional. All operations must address the same repository;
 * objects needed by a ref update must be available before that update becomes visible.
 */
public final class GitStorageApi {
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
