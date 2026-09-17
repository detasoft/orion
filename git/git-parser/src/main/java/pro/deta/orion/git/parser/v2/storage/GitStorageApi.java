package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackUpload;
import pro.deta.orion.git.parser.v2.read.PresenceGitObjectRead;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides the single external API for storage operations belonging to one repository.
 * Commands use this facade; ref, pack, and object stores remain internal implementation details.
 * Object resolution, operation-specific validation, access checks, and upstream forwarding belong to callers.
 * uploadNewPack(source) creates an isolated PackUpload, raw-byte storage, and an empty PackIndex before reading.
 * It supplies PackUpload with an owned Backend; file creation and publication remain internal to storage.
 * Upload uses static PackObjectParser entry parsing and incrementally stores original pack bytes and index
 * metadata. A streaming digest covers the original header and entries, excluding the trailing checksum.
 * Only bytes belonging to this pack enter the sink; subsequent protocol bytes remain available through the
 * same caller-owned source. Setup failure releases only resources created by that attempt.
 * The caller drives upload.hasNext/next; upload registers metadata in its index before returning each entry.
 * Resolution completes records through upload.index(). Storage controls placement of index records and waiting
 * chains; the API requires neither whole-index memory storage nor paths, files, or a final bulk transfer.
 * commit checks index.hasUnresolved, completes pending writes, and durably attaches pack to the existing index.
 * The index identifies missing bases for completion of a self-contained pack before publication.
 * Storage uses the object reader to restore any missing published bases.
 * commit and rollback belong to upload and never close source input.
 *
 * <p>Only published packs contribute objects to readObject, findPacksByObjectIds, and publishedPacks.
 * readObject(objectId, reader) invokes a caller-selected processor with the stored physical type, inflated
 * payload size, optional REF_DELTA base ObjectId from the index, and a bounded borrowed zlib source,
 * excluding pack headers and delta base references. Other physical types receive an empty baseId.
 * RawGitObjectRead processes compressed bytes; CompressedGitObjectRead supplies decompression for hash/content.
 * A delta payload remains instructions unless the caller supplies ResolvedGitObjectRead, which restores
 * REF_DELTA through recursive storage reads. That reader currently rejects OFS_DELTA explicitly.
 * Storage closes the source after processing; the reader cannot retain it. Nonnull results belong to the caller,
 * including any independently owned resources. Absence is Optional.empty() and does not invoke reader;
 * I/O and processing failures remain errors. Returning early must still respect and validate payload bounds.
 * exists uses PresenceGitObjectRead to check presence without decoding content; read failures still propagate.
 * Missing bases are appended before publication, updating the pack header and checksum to its final PackId.
 * Published packs retain no external-base dependency list and remain readable after ingestion closes.
 * Ref-update failures do not undo pack publication.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code uploadNewPack(source)} - create an upload with byte storage and an empty incremental index.</li>
 *   <li>{@code snapshotRefs()} - return refs and symbolic or detached HEAD from one consistent state.</li>
 *   <li>{@code updateRefs(updates, atomic)} - conditionally update refs and return one RefUpdateResult
 *       containing the original update per input, in request order.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 *   <li>{@code findPacksByObjectIds(objectIds)} - find published packs containing requested objects.</li>
 *   <li>{@code readObject(objectId, reader)} - process stored bytes and return the selected result.</li>
 *   <li>{@code readObjectPrefix(objectId, maxDataBytes)} - return type, size, and a bounded prefix.</li>
 * </ul>
 * Object and ref reads delegate to internal backends supplied at construction, without exposing them to callers.
 * The Path constructor opens pack storage in an existing repository directory. Loose objects and refs
 * remain unimplemented; the no-argument constructor provides an unconfigured facade for command scaffolds.
 * Object resolution and operation-specific policy belong to the caller;
 * all operations address the same repository, and ref targets must be available before updates become visible.
 */
public final class GitStorageApi {
    private final GitObjectStorage objects;
    private final GitRefsStorage refs;
    private final GitPackStorage packs;

    public GitStorageApi() {
        this(new GitObjectStorage(), new GitRefsStorage());
    }

    public GitStorageApi(Path repository) throws IOException {
        packs = new GitPackStorage(Objects.requireNonNull(repository, "repository"));
        objects = new GitObjectStorage(packs);
        refs = new GitRefsStorage();
    }

    GitStorageApi(GitObjectStorage objects, GitRefsStorage refs) {
        this.packs = null;
        this.objects = Objects.requireNonNull(objects, "objects");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public PackUpload uploadNewPack(BufferedByteInput source) throws IOException {
        return requirePacks().upload(this, source);
    }

    public <R> Optional<R> readObject(ObjectId objectId, GitObjectRead<R> reader) throws IOException {
        return objects.read(Objects.requireNonNull(objectId, "objectId"), Objects.requireNonNull(reader, "reader"));
    }

    public boolean exists(ObjectId objectId) throws IOException {
        return readObject(objectId, new PresenceGitObjectRead()).isPresent();
    }

    public RefsSnapshot snapshotRefs() {
        return refs.snapshot();
    }

    public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
        throw new UnsupportedOperationException("Ref updates are not implemented");
    }

    /**
     * Scans published pack indexes for each requested object.
     * Returns object IDs mapped to lists of containing pack IDs; an object can occur in multiple packs.
     * Objects absent from published packs are omitted, but may still exist as loose objects.
     * Index read failures must be reported as errors, not treated as absent objects. Results are derived
     * from published per-pack indexes; a shared lookup index is not required.
     *
     * @param objectIds object IDs to locate, not pack IDs
     * @return containing pack IDs grouped by object ID
     * @throws IOException if a published pack or index cannot be read
     */
    public Map<ObjectId, List<PackId>> findPacksByObjectIds(Collection<ObjectId> objectIds) throws IOException {
        return requirePacks().find(Objects.requireNonNull(objectIds, "objectIds"));
    }

    private GitPackStorage requirePacks() {
        if (packs == null) {
            throw new IllegalStateException("Persistent pack storage requires a repository path");
        }
        return packs;
    }
}
