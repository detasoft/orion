package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Access with refs declared at creation. Ref updates remain private until apply checks their original
 * values and publishes them under the index lock. Undeclared refs cannot be updated; declared refs need
 * not be changed. Apply finishes the access; discard and close abandon pending ref and HEAD changes.
 * Update results describe preparation, not publication. A single caller uses each access at a time.
 * Object ingestion and pack publication retain their independent lifetime.
 */
public interface GitIndexAccess extends AutoCloseable {
    void addObject(IndexedObject object) throws IOException;

    List<IndexedObject> objects(PackId packId) throws IOException;

    Optional<IndexedObject> findObject(PackId packId, ObjectId objectId) throws IOException;

    List<IndexedObject> locations(ObjectId objectId) throws IOException;

    Optional<PackMetadata> findPack(PackId packId) throws IOException;

    List<PackMetadata> packs(PackChecksum checksum) throws IOException;

    List<PackMetadata> packs() throws IOException;

    PackMetadata publishIndex(PackMetadata pack) throws IOException;

    RefsSnapshot snapshotRefs() throws IOException;

    void updateHead(Head head) throws IOException;

    List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic);

    void apply() throws IOException;

    default void discard() throws IOException {
        close();
    }

    void close() throws IOException;
}
