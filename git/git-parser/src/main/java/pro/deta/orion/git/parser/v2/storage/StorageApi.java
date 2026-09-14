package pro.deta.orion.git.parser.v2.storage;

/**
 * Provides access to refs, packs, and objects belonging to one repository.
 * Receive validation, access checks, and upstream forwarding belong to the operations using this API.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code refs()} - return the repository's GitRefsStorage.</li>
 *   <li>{@code packs()} - return the repository's GitPackStorage.</li>
 *   <li>{@code objects()} - return the repository's GitObjectStorage.</li>
 * </ul>
 * Method names and signatures are provisional. All three parts must address the same repository;
 * objects needed by a ref update must be available before that update becomes visible.
 */
public final class StorageApi {
}
