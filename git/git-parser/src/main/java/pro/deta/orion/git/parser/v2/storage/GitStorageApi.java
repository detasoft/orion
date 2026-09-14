package pro.deta.orion.git.parser.v2.storage;

/**
 * Provides the single external API for storage operations belonging to one repository.
 * Commands use this API directly; ref, pack, and object stores remain internal implementation details.
 * Receive validation, access checks, and upstream forwarding belong to the operations using this API.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code snapshotRefs()} - return a consistent snapshot of ref names and object IDs.</li>
 *   <li>{@code defaultHead()} - return the configured default HEAD target.</li>
 *   <li>{@code updateRefs(commands, atomic)} - conditionally update refs and return per-ref results.</li>
 *   <li>{@code publishPack(receivedPack)} - retain a validated pack and make its objects readable.</li>
 *   <li>{@code publishedPacks()} - list the metadata of published packs.</li>
 *   <li>{@code openPublishedPack(packId)} - open a published pack for reading.</li>
 *   <li>{@code readObject(objectId)} - return the object's type and full content, or absence.</li>
 *   <li>{@code readObjectPrefix(objectId, maxDataBytes)} - return type, full size, and a bounded prefix.</li>
 * </ul>
 * Method names and signatures are provisional. All operations must address the same repository;
 * objects needed by a ref update must be available before that update becomes visible.
 */
public final class GitStorageApi {
}
