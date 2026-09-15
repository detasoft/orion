package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores pack bytes and indexes internally behind GitStorageApi; files and paths stay inside storage.
 * Object resolution and operation-specific policy belong to callers. uploadNewPack creates a private
 * implementation of PackUpload with a PackObjectIterator constructed from the source and an internal
 * raw-byte sink. The iterator sends original bytes to that sink as it advances. Only bytes through the pack
 * checksum are retained; later protocol bytes remain available through the caller's source.
 * Resolved metadata and external base IDs accumulate separately.
 * The upload owns its parser, sink, commit, and rollback without a public upload ID. Rollback releases those
 * resources without closing source input or discarding a completed publication or another attempt's data.
 *
 * <p>All publishers of one repository share an internal GitLock. Acquire ownership for the verified PackId,
 * then check the manifest and reuse an existing publication or commit the prepared bytes, index, and manifest.
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
 *   <li>{@code uploadNewPack(source)} - create an upload with an iterator and private raw-byte sink.</li>
 *   <li>{@code commit(...)} - internally publish the upload's pack and confirmed external dependencies.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 * </ul>
 * Methods remain placeholders.
 */
final class GitPackStorage {
}
