package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores pack bytes and indexes internally behind GitStorageApi; files and paths stay inside storage.
 * Object resolution and operation-specific policy belong to callers. uploadNewPack creates a private
 * implementation of PackUpload with an empty storage-provided PackIndex and an internal raw-byte sink.
 * Static PackObjectParser methods consume entries into that sink with bounded buffers. Upload accumulates
 * the pack checksum incrementally, excluding the trailer from the digest. Only bytes through that trailer
 * are retained; later protocol bytes remain available through the caller's source.
 * PackIndex accumulates provisional metadata, resolved ObjectIds, external bases, and waiting dependencies
 * directly in storage. It need not reside entirely in memory or share the byte store's backing format.
 * Each upload owns a PackByteStore: parsing borrows its WritableByteChannel view, while upload.readObject
 * opens retained entries by offset and exposes inflated payload through caller-owned GitObjectRead handles.
 * Raw reads remain internal to storage. The initial implementation uses a private FileChannel behind
 * PackByteStore, with no public file
 * handles or additional memory tier.
 * The upload owns its parsing state, sink, index, commit, and rollback without a public upload ID. Rollback releases
 * resources without closing source input or discarding a completed publication or another attempt's data.
 *
 * <p>All publishers of one repository share an internal GitLock. Acquire ownership for the verified PackId,
 * then check durable publication metadata and reuse an existing publication or publish this pack and index.
 * Before publication, require completed parsing and index.hasUnresolved() == false. Finish pending byte and
 * index writes before publishing their durable association. The populated index stays in storage; no complete
 * collection must be transferred at commit. Waiting lookup structures can be discarded after publication.
 * Release ownership after publication writes and cleanup. Different pack IDs publish independently; a waiter
 * rechecks the manifest rather than assuming the preceding attempt succeeded. Retry after an uncertain I/O
 * outcome also checks the manifest. Complete publications survive recovery; incomplete staging stays invisible.
 *
 * <p>Only published packs contribute to object lookup and outgoing pack selection.
 * Published objects and their external bases stay readable after ingestion closes.
 * Store only externalBaseIds; locate and preserve backing objects without storing
 * externalPackIds. Ref rejection never undoes an already committed publication.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code uploadNewPack(source)} - create an upload with byte storage and an empty PackIndex.</li>
 *   <li>{@code commit(...)} - internally publish the upload's pack and confirmed external dependencies.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 * </ul>
 * Methods remain placeholders.
 */
final class GitPackStorage {
}
