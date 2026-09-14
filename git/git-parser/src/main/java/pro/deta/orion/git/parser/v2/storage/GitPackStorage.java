package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores validated packs internally behind GitStorageApi for reuse and delivery.
 * Callers outside this package access these operations only through GitStorageApi.
 * Receives bytes into quarantine and checks physical pack format and checksum without reading external bases.
 * The calling operation owns object resolution and receive policy; storing a pack does not update refs or
 * imply that a push was accepted.
 *
 * <p>Reception returns the verified PackId and a preliminary scan index after all pack bytes are stored.
 * Incomplete reception is identified only inside storage. Callers address quarantined packs by PackId;
 * concurrent receptions of identical content require storage-owned coordination and cleanup so one operation
 * cannot discard another operation's data. No upload identifiers, files, or paths cross the API.
 * A checked checksum does not imply that all objects are resolved. Publication requires the final object
 * index and external-base dependencies produced by resolution. Different pack IDs publish independently.
 * Refs remain subject to each operation's own checks and conditional updates even when pack data is reused.
 *
 * <p>All publishers of one repository share an internal GitLock. Acquire ownership for the verified packId,
 * then check the manifest and either reuse the published pack or publish the prepared data. Release ownership
 * after all publication writes and failure cleanup finish. Awakening after another attempt does not prove
 * success; only the owner rechecks the manifest and may retry publication using its own prepared pack.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code publish(packId)} - publish the quarantined pack after object resolution and index preparation.</li>
 *   <li>{@code publishedPacks()} - list the metadata of published packs.</li>
 *   <li>{@code openPublishedPack(packId)} - open a published pack for reading.</li>
 * </ul>
 * Method names and signatures are provisional. Storage locates prepared data and external-base dependencies
 * by PackId. Stored objects must remain readable after the ingestion session closes, including when a
 * thin pack depends on existing objects. A later ref rejection need not remove an already published pack.
 */
final class GitPackStorage {
}
