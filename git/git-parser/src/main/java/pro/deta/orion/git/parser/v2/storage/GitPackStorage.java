package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores pack bytes and indexes internally behind GitStorageApi; files and paths stay inside storage.
 * Object resolution and operation-specific policy belong to callers. The reception API remains to be defined.
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
 *   <li>{@code openPack(packId)} - open original quarantined or published bytes as a caller-owned PackRead.</li>
 *   <li>{@code publish(packId)} - durably publish a prepared pack and its dependencies.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 * </ul>
 * Methods remain placeholders.
 */
final class GitPackStorage {
}
