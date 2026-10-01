package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.api.Modification;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Access with refs declared at creation. Ref updates remain private until apply checks their original
 * values and publishes the changed refs atomically. Undeclared refs cannot be updated; declared refs need
 * not be changed. Apply finishes the access; discard abandons pending ref and HEAD changes.
 * Update results describe preparation, not publication. A single caller uses each access at a time.
 * Object ingestion and pack publication retain their independent lifetime.
 * Each ingestion owns a distinct PackId, even for identical content. Finish its object writes before
 * publishing its index; callers must not write and publish the same PackId concurrently.
 * A ref conflict does not remove ingested objects or published packs.
 * An access created for a pack owns that pack until apply or discard. Close the pack's storage
 * before finishing the access.
 */
public interface GitIndexAccess extends Modification {
    Optional<PackId> packId();

    void addObject(IndexedObject object) throws IOException;

    List<IndexedObject> objects(PackId packId) throws IOException;

    Optional<IndexedObject> findObject(PackId packId, ObjectId objectId) throws IOException;

    List<IndexedObject> locations(ObjectId objectId) throws IOException;

    Optional<PackMetadata> findPack(PackId packId) throws IOException;

    List<PackMetadata> packs(PackChecksum checksum) throws IOException;

    List<PackMetadata> packs() throws IOException;

    PackMetadata publishIndex(PackMetadata pack) throws IOException;

    RefsSnapshot snapshotRefs(RefSelection selection) throws IOException;

    default Optional<ObjectId> findRef(RefId ref) throws IOException {
        return Optional.ofNullable(snapshotRefs(new RefSelection.One(ref)).refs().get(ref));
    }

    void updateHead(Head head) throws IOException;

    List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic);

}
