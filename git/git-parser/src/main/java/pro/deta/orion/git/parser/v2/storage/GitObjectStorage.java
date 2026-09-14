package pro.deta.orion.git.parser.v2.storage;

/**
 * Reads Git objects internally behind GitStorageApi independently of their loose or packed representation.
 * Callers outside this package access these operations only through GitStorageApi.
 * Provides the object view used by graph validation, file loading, and outgoing pack construction.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code read(objectId)} - open an ObjectRead for positional reads into caller-owned ByteBuffers,
 *       or return absence; I/O failures remain errors.</li>
 *   <li>{@code readPrefix(objectId, maxDataBytes)} - return type, full size, and a bounded content prefix.</li>
 * </ul>
 * Method names and signatures are provisional. This view includes objects published through GitPackStorage;
 * callers must not need to know which pack contains an object or how its delta bases are resolved.
 */
final class GitObjectStorage {
}
