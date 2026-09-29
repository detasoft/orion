package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
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
 * Repository index, independent of pack storage; its owner controls its lifetime.
 * addObject accepts verified objects only, including fully resolved DELTAs; identical rows are idempotent.
 * objects and findObject expose pending entries explicitly scoped to a pack ID; objects are ordered by offset.
 * All other queries return only published packs and their objects, as immutable snapshots.
 * Publication atomically exposes a completed pack after checking object count and base membership,
 * and seals its rows. Identical publication is idempotent; several packs may share a checksum.
 * The repository hash algorithm is fixed at creation, defaults to SHA-1 and applies to all stored IDs.
 * The caller finalizes storage and verifies the pack checksum before publication; the index never reads bytes.
 * Callers verify that new ref targets and detached HEAD targets exist in storage before updating the index.
 * The index validates ref names and expected old values and applies atomic ref updates.
 */
public interface GitIndexApi extends AutoCloseable {
    GitHashAlgorithm hashAlgorithm();

    void addObject(IndexedObject object) throws IOException;

    List<IndexedObject> objects(PackId packId) throws IOException;

    Optional<IndexedObject> findObject(PackId packId, ObjectId objectId) throws IOException;

    List<IndexedObject> locations(ObjectId objectId) throws IOException;

    Optional<PackMetadata> findPack(PackId packId) throws IOException;

    List<PackMetadata> packs(PackChecksum checksum) throws IOException;

    List<PackMetadata> packs() throws IOException;

    PackMetadata publishPack(PackMetadata pack) throws IOException;

    RefsSnapshot snapshotRefs() throws IOException;

    void updateHead(Head head) throws IOException;

    List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic);

    void close() throws IOException;
}
