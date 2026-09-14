package pro.deta.orion.git.parser.v2.storage;

/**
 * Stores validated packs internally behind GitStorageApi for reuse and delivery.
 * Callers outside this package access these operations only through GitStorageApi.
 * Pack parsing, quarantine, and receive policy belong to the calling operation; storing a pack does not
 * update refs or imply that a push was accepted.
 *
 * <p>Publication receives a prepared pack whose packId is its verified checksum. For a file-backed store,
 * locking by packId protects publication after ingestion, not reception of the incoming stream. Separate
 * uploads use isolated temporary areas until their pack IDs are known. Under the publication lock, check
 * whether the same pack is already published and reuse it; different pack IDs publish independently.
 * Refs remain subject to each operation's own checks and conditional updates even when pack data is reused.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code publish(receivedPack)} - retain a validated pack and make its objects readable.</li>
 *   <li>{@code publishedPacks()} - list the metadata of published packs.</li>
 *   <li>{@code openPublishedPack(packId)} - open a published pack for reading.</li>
 * </ul>
 * Method names and signatures are provisional. The received pack carries its bytes and external-base
 * dependencies. Stored objects must remain readable after the ingestion session closes, including when a
 * thin pack depends on existing objects. A later ref rejection need not remove an already published pack.
 */
final class GitPackStorage {
}
