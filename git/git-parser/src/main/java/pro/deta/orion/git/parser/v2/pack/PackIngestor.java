package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectRead;

import java.io.IOException;
import java.util.Objects;

/**
 * Coordinates pack resolution for PushCommand through PackUpload.
 * While upload.hasNext(), obtain upload.next(), delegate the parsed result to resolver.attemptResolve,
 * and close result.object() in finally without masking a primary failure. This closes a read handle, not
 * upload-owned original pack bytes needed by deferred chains. Hashed results have no content resources to release.
 * This is a consumer of storage and parsing, not part of either API or a required callback implementation.
 * Create and close a GitPackObjectResolver for this upload. Delegate every entry, including full objects,
 * to attemptResolve(result), which uses full-object hashes or resolves delta content and follows waiting chains.
 * This ingestor never reads, decompresses, or hashes payload bytes and never applies delta instructions.
 * PackUpload owns parsing, full-object hashing, and retaining bytes; the resolver owns reconstruction,
 * hashing reconstructed results, index completion, and base reads. Upload's storage-provided PackIndex
 * holds records and waiting chains. This ingestor keeps no second chain graph or payload cache. getObject(id)
 * provides content to other consumers; the ingestion loop does not need to open or read those handles.
 * upload.next parses and retains each complete physical entry with bounded buffers and registers its metadata
 * in the provisional index before handing it to this ingestor.
 * Missing indexed IDs do not prove external dependencies because later entries may resolve to those IDs.
 *
 * <p>When an entry cannot yet be resolved, it stays in the upload's waiting chains. Each successful resolution
 * triggers dependent entries inside the same attemptResolve call, so one input entry can complete several
 * waiting chains. The upload indexes waiting dependencies by base ObjectId or absolute entry offset;
 * the ingestor neither scans all unresolved entries after each object nor manages chain removal.
 * Missing bases are deferred dependencies, not yet evidence of an invalid pack. Record confirmed external
 * dependencies through upload.index().addExternalBaseId after iteration. After hasNext returns false, obtain
 * upload.packId() and call upload.commit(packId). Commit itself checks index.hasUnresolved and rejects unfinished
 * chains; this ingestor does not retrieve a list or duplicate that check. Confirmed external bases may remain
 * nonempty. Failed parsing or resolution must not lead to commit.
 *
 * <p>The constructor borrows one upload and creates the resolver owned by this ingestor. resolvePack()
 * iterates parsed results, closes their read handles, delegates resolution, and requests upload commit.
 * recordExternalBases remains a placeholder for final classification through the completed index; until
 * that work is implemented, ingestion cannot reach publication. No independent pending map is introduced.
 * close() releases owned resolver and ingestion resources. PushCommand performs command policy checks and
 * rolls back upload staging in finally without undoing a successful commit. This consumer borrows the upload
 * and never closes source input.
 * The parser has no storage dependency and never calls back into this class. The orchestration loop is
 * present, while parsing, resolution, dependency classification, and publication are still incomplete.
 */
public final class PackIngestor implements AutoCloseable {
    private final PackUpload upload;
    private final GitPackObjectResolver resolver;

    public PackIngestor(PackUpload upload) {
        this.upload = Objects.requireNonNull(upload, "upload");
        this.resolver = new GitPackObjectResolver(upload);
    }

    public void resolvePack() throws IOException {
        while (upload.hasNext()) {
            PackObjectParser.Result result = upload.next();
            try (GitObjectRead object = result.object()) {
                resolver.attemptResolve(result);
            }
        }
        recordExternalBases();
        upload.commit(upload.packId());
    }

    private void recordExternalBases() throws IOException {
        throw new UnsupportedOperationException("External base classification is not implemented");
    }

    @Override
    public void close() throws IOException {
        resolver.close();
    }
}
