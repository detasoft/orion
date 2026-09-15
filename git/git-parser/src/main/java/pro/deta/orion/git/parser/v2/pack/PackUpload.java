package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectRead;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;

/**
 * Owns one upload's input position, checksum state, retained pack bytes, and storage-provided PackIndex.
 * index returns the same borrowed index, initially empty. Storage controls its placement and may write
 * records and waiting dependencies incrementally without keeping the entire index in memory.
 * storage returns the owning repository's borrowed GitStorageApi for reading published external bases.
 * Distinct attempts own isolated resources even if their eventual PackIds match.
 *
 * <p>hasNext validates the pack header and tracks its declared entry count. next uses the static
 * PackObjectParser.parseEntry to consume one complete entry with bounded buffers into the internal raw sink,
 * then calls index.addEntry before returning metadata. Even full objects initially enter the index as
 * unresolved. Sink or index failures stop the attempt and prevent successful completion.
 * next never returns null and throws NoSuchElementException after successful exhaustion. Repeated hasNext
 * calls do not consume another entry. After the declared entries, hasNext verifies the checksum before
 * returning false. Truncation and malformed input are IOException, not normal exhaustion.
 * packId returns the verified checksum after hasNext has returned false, including for an empty pack;
 * before successful completion it fails with IllegalStateException. It performs no I/O.
 * Original bytes, including pack header, entries, and checksum, are retained exactly once. The checksum is
 * computed over header and entries, excluding the trailing checksum itself. Subsequent protocol bytes stay
 * available through the same caller-owned source and never enter the raw sink.
 * During the first pass, each original byte block is both retained and fed once to a streaming digest
 * accumulator, such as MessageDigest.update. The trailing checksum is retained but excluded from update;
 * compare it with the final digest to obtain PackId. Neither hashing nor entry scanning loads the whole pack
 * into memory. Inflated payload is inspected with bounded buffers to locate and validate zlib boundaries,
 * then discarded; first-pass index records contain offsets, types, lengths, and base references.
 *
 * <p>readObject(entryOffset) opens a caller-owned GitObjectRead for an indexed entry's inflated payload:
 * object content or delta instructions. The absolute offset identifies an entry already returned by next,
 * whose original bytes are fully retained. A negative or unknown entry offset fails with IllegalArgumentException.
 * The handle exposes entry.type and entry.inflatedSize; its own read offsets address inflated payload bytes.
 * Reads decompress retained data without rereading transport input or advancing iteration. Opening the handle
 * does not require loading the whole payload; content is read as needed through the GitObjectRead contract.
 * Close handles before rollback; closing a handle does not close upload. Access after rollback fails with
 * ClosedChannelException. Reconstruction and hashing belong to the resolver, which follows indexed offsets,
 * opens reads for required entries and bases, and records computed ObjectIds. The ingestor reads no payloads.
 *
 * <p>commit(packId) requires completed iteration and a matching verified checksum, even for an empty pack.
 * It checks index.hasUnresolved itself and refuses publication with IOException when any unfinished record
 * or chain remains. Index lookup errors also prevent publication; no separate ingestor check is required.
 * Confirmed external base IDs must have been recorded before commit. Storage finishes pending byte and index
 * writes, preserves external bases, and durably publishes the association between this pack and its already
 * populated index under the PackId lock. No complete index collection is transferred from caller memory.
 * Temporary waiting structures may then be removed; completed index records remain published.
 * Mutations after commit are rejected. An I/O failure during publication can have an uncertain outcome;
 * retries inspect durable publication metadata. Validation rejection publishes nothing.
 *
 * <p>rollback releases byte storage and index resources belonging to this attempt and discards only unpublished
 * staging. It is idempotent and safe after commit: published data and other attempts remain intact.
 * Call it in finally without masking the original failure. Neither outcome closes caller-owned source input.
 * Recovery ignores incomplete staging. Methods are sequential. This is a contract for future implementation.
 */
public interface PackUpload {
    GitStorageApi storage();

    PackIndex index();

    boolean hasNext() throws IOException;

    PackObjectParser.Entry next() throws IOException;

    PackId packId();

    GitObjectRead readObject(long entryOffset) throws IOException;

    void commit(PackId packId) throws IOException;

    void rollback() throws IOException;
}
