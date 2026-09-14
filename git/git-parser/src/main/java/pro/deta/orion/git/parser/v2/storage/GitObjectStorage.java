package pro.deta.orion.git.parser.v2.storage;

/**
 * Reads Git objects by ID independently of their loose or packed representation.
 * Provides the object view used by graph validation, file loading, and outgoing pack construction.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code read(objectId)} - return the object's type and full content, or absence.</li>
 *   <li>{@code readPrefix(objectId, maxDataBytes)} - return type, full size, and a bounded content prefix.</li>
 * </ul>
 * Method names and signatures are provisional. This view includes objects published through GitPackStorage;
 * callers must not need to know which pack contains an object or how its delta bases are resolved.
 */
public final class GitObjectStorage {
}
