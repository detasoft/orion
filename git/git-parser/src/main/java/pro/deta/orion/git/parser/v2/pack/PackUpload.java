package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Owns one pack upload: its iterator, byte sink, accumulated object index, and external dependencies.
 * Distinct instances isolate attempts even when their final PackIds match. iterator returns the same
 * borrowed parser for the lifetime of the attempt. Its raw-byte sink belongs to this upload and receives
 * bytes directly from the iterator as parsing progresses, independently of resolved index entries.
 * Sink I/O failures propagate through iteration as IOException. There is no separate public write method.
 * storage returns the owning repository's GitStorageApi for reading published external bases. This is a
 * borrowed facade, not a new connection or resource owned by the resolver. Upload-local reads and find
 * remain separate from published-object lookup through storage.
 * addObject associates a fully consumed entry with its resolved ID, logical type, and content size.
 * Results may arrive in dependency order; identical repeats are harmless and conflicting results fail.
 * addExternalBaseId accumulates confirmed external dependencies, not every encountered REF_DELTA base.
 * Reconstruction, hashing, and dependency ordering belong to the caller.
 *
 * <p>read provides positional access to the original raw bytes already accepted by this upload's sink,
 * including bytes still buffered internally. It never advances the iterator or reads more source input.
 * Offsets are relative to the pack header. Any writable heap, direct, or sliced ByteBuffer is supported;
 * reads advance position, preserve limit, and do not retain the buffer. Partial reads are allowed.
 * An empty destination returns zero; otherwise read returns a positive count or -1 at or beyond the stored end.
 * That end may grow with further iteration. Negative offsets fail with IllegalArgumentException,
 * null buffers with NullPointerException, and read-only buffers with ReadOnlyBufferException.
 * I/O failures remain IOException; reads after rollback fail with ClosedChannelException.
 * find searches only objects successfully registered through addObject in this upload and returns their
 * original physical entry, including its offset and delta representation. Duplicate ObjectIds may return
 * any matching entry. Absence is Optional.empty(), not proof that a base is external: it may resolve later.
 * Lookup errors remain IOException. Neither lookup nor raw reads resolve objects or query other packs.
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

    int read(long offset, ByteBuffer destination) throws IOException;

    Optional<PackObjectIterator.Entry> find(ObjectId objectId) throws IOException;

    void addObject(PackObjectIterator.Entry entry, ObjectId objectId, ObjectType type, long size)
            throws IOException;

    void addExternalBaseId(ObjectId externalBase) throws IOException;

    void commit(PackId packId) throws IOException;

    void rollback() throws IOException;
}
