package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi.PackIndex;

import java.io.IOException;
import java.util.Set;

/**
 * Owns pack-object resolution for PushCommand, using an ordinary loop over index.enumerator().next().
 * This is a consumer of storage and pack parsing, not part of either API or a required callback implementation.
 * For full objects, streams the payload into hashing and calls index.addObject with the resolved metadata.
 * For OFS_DELTA, finds the base through index.entryAt; for REF_DELTA, searches index.find and repository storage.
 * Missing or unresolved bases defer an entry. The ingestor owns pending dependencies and restores them when
 * bases become available, rereading retained bytes through index.read rather than retaining all payloads.
 * A missing indexed ID does not prove a base is external; classification accounts for later resolved entries.
 * After enumeration returns null, finishes pending work or fails, then returns confirmed externalBaseIds.
 *
 * <p>Preliminary methods: resolvePack(index) drives enumeration and resolution; close() releases owned base
 * reads and temporary resolution resources. PushCommand creates and closes this ingestor, performs policy
 * checks, and commits or closes the index. The ingestor borrows the index and enumerator without closing them.
 * The parser has no storage dependency and never calls back into the ingestor. Method bodies are placeholders.
 */
public final class PackIngestor implements AutoCloseable {
    public Set<ObjectId> resolvePack(PackIndex index) throws IOException {
        throw new UnsupportedOperationException("Pack resolution is not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Pack resolution cleanup is not implemented");
    }
}
