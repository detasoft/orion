package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Owns one pack upload: its iterator, byte sink, resolution chains, object index, and external dependencies.
 * Distinct instances isolate attempts even when their final PackIds match. iterator returns the same
 * borrowed parser for the lifetime of the attempt. Its raw-byte sink belongs to this upload and receives
 * bytes directly from the iterator as parsing progresses, independently of resolved index entries.
 * Sink I/O failures propagate through iteration as IOException. There is no separate public write method.
 * storage returns the owning repository's GitStorageApi for reading published external bases. This is a
 * borrowed facade, not a new connection or resource owned by the resolver. Upload-local reads and find
 * remain separate from published-object lookup through storage.
 * As the iterator yields each entry, upload creates its provisional index record keyed by original offset,
 * retaining physical metadata and its base reference before ObjectId is known. addObject completes that same
 * record after full consumption with the resolved ID, logical type, and content size. The offset is already
 * provided by entry; no separate offset argument or second collection of physical entries is needed.
 * Its type argument must be COMMIT, TREE, BLOB, or TAG, even when entry.type is a delta encoding.
 * These registrations are the accumulated future pack index, retaining ObjectId, original offset, logical
 * type, and size for every resolved physical entry until commit or rollback. Object IDs remain available
 * after their waiting chains complete; removing waiting state never removes index entries or the base links
 * needed to restore content. commit publishes this accumulated index together with the original pack bytes.
 * No separate duplicate list of ObjectIds is required: the index entries are their authoritative collection.
 * Maintain direct lookup by offset from parsing onward and by ObjectId after registration, both addressing
 * the same index records. Neither lookup scans the pack or walks every index entry. Repeated ObjectIds may
 * identify several physical records; lookup by ID can choose one while the offset index retains every entry.
 * These upload-time lookups do not prescribe the on-disk index format prepared at commit.
 * Results may arrive in dependency order; identical repeats are harmless and conflicting results fail.
 * addExternalBaseId accumulates confirmed external dependencies, not every encountered REF_DELTA base.
 * Pack parsing and decompression belong to this upload, using its PackObjectIterator internally.
 * Reconstruction, hashing, and walking newly unblocked chains belong to the resolver.
 * The upload retains each encountered entry's metadata and payload bytes, including unresolved entries.
 * Entry baseOffset and baseId describe resolution chains; registered object IDs connect REF_DELTA links
 * to local entries as resolution progresses. OFS_DELTA links address earlier entries regardless of whether
 * they are resolved. Chain structure and completed index results belong to the upload, not the resolver.
 * Retaining content means retaining the original bytes needed to restore it, not keeping all objects in RAM.
 * unresolvedEntries returns a metadata snapshot of provisional index records still missing a resolved ID.
 * Entries are tracked by pack offset, so repeated object IDs do not hide unresolved physical entries.
 * As parsing encounters delta entries, the upload indexes waiting chain ends by base ObjectId for REF_DELTA
 * or absolute base offset for OFS_DELTA. Each base can have several waiting dependents. This retained lookup
 * belongs to the upload; the resolver does not rebuild it by scanning every unresolved entry.
 * waitingFor(objectId, entryOffset) returns a snapshot of unresolved direct dependents of a resolved entry,
 * combining both lookup keys without duplicates. Querying does not remove waiting entries. addObject removes
 * only the successfully registered entry from unresolved state and its base's waiting list; its dependents
 * remain discoverable until they too resolve. Repeating this lookup after each resolution closes whole chains.
 *
 * <p>read(entry, offset, destination) provides positional access to an entry's inflated payload: full object
 * content or delta instructions, according to entry.type. The upload parses the pack representation and
 * decompresses it; callers never decode raw pack bytes. The entry must have been yielded by this upload's
 * iterator. If its current payload is not yet fully retained, upload finishes consuming and preserving it
 * before serving the read. This does not advance to another entry or invalidate iterator metadata.
 * Deferred entries are read from retained bytes without rereading source input. Offsets are relative to the
 * inflated payload, whose length is entry.inflatedSize. Any writable heap, direct, or sliced buffer is supported;
 * reads advance position, preserve limit, and do not retain the buffer. Partial reads are allowed.
 * An empty destination returns zero; otherwise read returns a positive count or -1 at or beyond payload end.
 * An entry from another upload or a negative offset fails with IllegalArgumentException,
 * null buffers with NullPointerException, and read-only buffers with ReadOnlyBufferException.
 * I/O failures remain IOException; reads after rollback fail with ClosedChannelException.
 * find(objectId) searches only objects successfully registered through addObject in this upload and returns their
 * original physical entry, including its offset and delta representation. Duplicate ObjectIds may return
 * any matching entry. Absence is Optional.empty(), not proof that a base is external: it may resolve later.
 * find(entryOffset) returns metadata for a previously encountered entry at an absolute pack offset, including
 * unresolved entries, so the resolver can follow OFS_DELTA bases. It does not scan ahead or require addObject.
 * Lookup errors remain IOException. Neither lookup nor payload reads resolve objects or query other packs.
 *
 * <p>commit(packId) accepts the verified checksum supplied by the caller for the accumulated pack bytes.
 * The caller completes iteration, resolves every physical entry, and records confirmed external dependencies
 * before committing. Empty packs also require completed iteration and checksum verification. Storage
 * preserves external bases and durably publishes bytes, index, and manifest under the supplied PackId lock.
 * An I/O error can have an uncertain commit outcome; retries inspect the durable manifest.
 * rollback releases the owned parser and sink and discards only this attempt's unpublished staging.
 * It is idempotent and never removes committed data or another attempt's resources. The caller invokes
 * it in finally, including after commit; cleanup must not mask the original operation failure.
 * Neither outcome closes the caller's source input. Recovery ignores incomplete staging.
 * Methods are used sequentially within the owning operation. This is a contract for future implementation.
 */
public interface PackUpload {
    GitStorageApi storage();

    PackObjectIterator iterator();

    int read(PackObjectIterator.Entry entry, long offset, ByteBuffer destination) throws IOException;

    Optional<PackObjectIterator.Entry> find(ObjectId objectId) throws IOException;

    Optional<PackObjectIterator.Entry> find(long entryOffset) throws IOException;

    List<PackObjectIterator.Entry> unresolvedEntries() throws IOException;

    List<PackObjectIterator.Entry> waitingFor(ObjectId objectId, long entryOffset) throws IOException;

    void addObject(PackObjectIterator.Entry entry, ObjectId objectId, ObjectType type, long size)
            throws IOException;

    void addExternalBaseId(ObjectId externalBase) throws IOException;

    void commit(PackId packId) throws IOException;

    void rollback() throws IOException;
}
