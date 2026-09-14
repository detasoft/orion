package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores validated packs internally behind GitStorageApi for reuse and delivery.
 * Callers outside this package access these operations only through GitStorageApi.
 * Pack parsing, quarantine, and receive policy belong to the calling operation; storing a pack does not
 * update refs or imply that a push was accepted.
 *
 * <p>Publication receives a PackUploadId identifying a completed, validated upload inside repository storage.
 * Its prepared data includes the verified PackId, pack bytes, index, and external-base object IDs.
 * Separate uploads have distinct upload IDs even when their verified PackIds match; no files or paths cross
 * the publication API. Coordination uses the verified PackId and protects publication after ingestion, not
 * reception of the incoming stream. Different pack IDs publish independently.
 * Refs remain subject to each operation's own checks and conditional updates even when pack data is reused.
 *
 * <p>All publishers of one repository share an internal GitLock. Acquire ownership for the verified packId,
 * then check the manifest and either reuse the published pack or publish the prepared data. Release ownership
 * after all publication writes and failure cleanup finish. Awakening after another attempt does not prove
 * success; only the owner rechecks the manifest and may retry publication using its own prepared pack.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code publish(uploadId)} - retain the validated upload's pack and return its verified PackId.</li>
 *   <li>{@code publishedPacks()} - list the metadata of published packs.</li>
 *   <li>{@code openPublishedPack(packId)} - open a published pack for reading.</li>
 * </ul>
 * Method names and signatures are provisional. Storage locates prepared data and external-base dependencies
 * by upload ID. Stored objects must remain readable after the ingestion session closes, including when a
 * thin pack depends on existing objects. A later ref rejection need not remove an already published pack.
 */
final class GitPackStorage {
}
