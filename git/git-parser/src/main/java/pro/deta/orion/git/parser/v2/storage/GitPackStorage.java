package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores pack bytes and indexes internally behind GitStorageApi; files and paths stay inside storage.
 * Object resolution and operation-specific policy belong to callers. uploadNewPack creates a private
 * implementation of GitStorageApi.PackIndex with an independent PackEnumerator over retained input.
 * Original bytes are stored as the caller drives enumeration. The index registers physical entries and
 * accepts resolved metadata incrementally; it owns publication and rollback, without a public upload ID.
 * Input bytes following the checksum remain available to the caller. Closing the index closes its parser
 * and releases staging, but never closes source input or discards a completed publication.
 *
 * <p>All publishers of one repository share an internal GitLock. Acquire ownership for the verified PackId,
 * then check the manifest and reuse an existing publication or commit the prepared bytes, index, and manifest.
 * Release ownership after publication writes and cleanup. Different pack IDs publish independently; a waiter
 * rechecks the manifest rather than assuming the preceding attempt succeeded. Retry after an uncertain I/O
 * outcome also checks the manifest. Complete publications survive recovery; incomplete staging stays invisible.
 *
 * <p>Only published packs contribute to object lookup and outgoing pack selection. Open PackRead handles pin
 * unchanged bytes through publication or cleanup. Published objects and their external bases stay readable
 * after ingestion closes. Store only externalBaseIds; locate and preserve backing objects without storing
 * externalPackIds. Ref rejection never undoes an already committed publication.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code uploadNewPack(source)} - create an isolated upload index with its borrowed enumerator.</li>
 *   <li>{@code openPack(packId)} - open original quarantined or published bytes as a caller-owned PackRead.</li>
 *   <li>{@code commit(...)} - internally publish the index's pack and confirmed external dependencies.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 * </ul>
 * Methods remain placeholders.
 */
final class GitPackStorage {
}
