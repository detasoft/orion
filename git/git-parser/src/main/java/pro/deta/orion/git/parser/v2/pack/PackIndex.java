package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;
import java.util.Optional;

/**
 * Storage-provided index for one upload, initially empty and populated incrementally.
 * Storage controls placement and buffering of both index records and unfinished resolution chains.
 * Implementations may persist records as they arrive; neither a complete in-memory index nor files exposed
 * to callers are required. PackUpload owns this handle's lifecycle and publishes or discards it with the pack.
 * Distinct attempts have isolated indexes even when their final PackIds match. Reads observe successful
 * writes within the upload; unpublished records never contribute to repository-wide object lookup.
 *
 * <p>addEntry creates a provisional record keyed by original offset with physical metadata and base links.
 * Upload calls it before returning each parsed result. Every record starts unresolved; upload immediately
 * completes full objects through addObject using IDs returned by HashedGitObjectRead.
 * Delta entries wait for the resolver.
 * addObject completes that same record with ObjectId, logical type, and restored content size; the offset
 * already belongs to entry. Allowed logical types are COMMIT, TREE, BLOB, and TAG. Exact repeats are harmless;
 * conflicting data or completing an unregistered entry fails. Records retain all ObjectIds needed for the
 * published index; removing waiting state never removes completed records or links needed to restore content.
 *
 * <p>find(offset) returns encountered physical metadata, including unresolved entries. find(objectId) returns
 * resolved physical metadata. Both use indexed lookup without scanning every entry or the pack bytes.
 * Several offsets may contain the same ObjectId; ID lookup may choose one, while offset lookup retains all.
 * Absence is Optional.empty(), not proof that a REF_DELTA base cannot appear later in the same pack.
 *
 * <p>addEntry also records waiting delta dependencies by base ObjectId or absolute base offset.
 * waitingFor(objectId, entryOffset) returns one unresolved direct dependent matching either key, or absence.
 * It neither removes the entry nor materializes all dependents. addObject removes only the completed entry's
 * unresolved status and its waiting dependency; its own dependents remain discoverable. Repeating lookup
 * after successful resolution walks whole chains and branches. Failed attempts leave waiting state intact.
 * hasUnresolved reports whether any provisional record remains, including an incompletely registered full object.
 * Commit uses this query without retrieving a list of unfinished chains.
 *
 * <p>After parsing and resolution, nextExternalBase returns one referenced base absent from this pack.
 * It requires all entries to be resolved, neither consumes the result nor treats a failed append as success.
 * After the caller appends and registers that base as a full object, the next call skips it and returns
 * another missing base. Candidate IDs are temporary disk state, never a permanent external dependency list.
 * Finalization requires all bases to be internal and dependency chains to be acyclic. The storage owner
 * updates pack bytes, object count, and checksum before publishing a self-contained pack with its index.
 * Permanent records contain only offset metadata and ObjectId lookup; processing state is discarded.
 *
 * <p>Future optimization, not implemented: keep an upload-local LRU of restored base bytes alongside this
 * index. During parsing, future consumers are unknown; an observed reference to a base is only a reuse hint.
 * When that base is read and restored for resolution, its bytes may enter the LRU; subsequent use refreshes
 * recency. Bound cached payload by total retained byte size, not entry count, and bypass oversized bases.
 * This is temporary, optional memory state, never part of the published index or a separate persisted copy.
 * Eviction removes only cached bytes, preserving object records and waiting chains. A miss repeats reading
 * and reconstruction from the original pack or published external storage. Eviction must not invalidate an
 * active read handle; the cache budget does not bound the working memory needed by active reconstruction.
 * Until this optimization is introduced, retain the current reread behavior without future-consumer tracking.
 *
 * <p>PackUpload.commit rejects unresolved state, finishes pending storage writes, and publishes the association
 * between the pack and this already populated index. Temporary waiting structures may then be discarded.
 * Rollback discards only this attempt's unpublished resources. Methods are sequential; mutations after commit
 * fail with IllegalStateException and access after rollback with ClosedChannelException. I/O errors propagate
 * as IOException, never absence or false. The disk backend and its finalization remain internal to storage.
 */
public interface PackIndex {
    void addEntry(PackObjectParser.Entry entry) throws IOException;

    void addObject(PackObjectParser.Entry entry, ObjectId objectId, ObjectType type, long size)
            throws IOException;

    Optional<PackObjectParser.Entry> find(ObjectId objectId) throws IOException;

    Optional<PackObjectParser.Entry> find(long entryOffset) throws IOException;

    Optional<PackObjectParser.Entry> waitingFor(ObjectId objectId, long entryOffset) throws IOException;

    boolean hasUnresolved() throws IOException;

    Optional<ObjectId> nextExternalBase() throws IOException;
}
