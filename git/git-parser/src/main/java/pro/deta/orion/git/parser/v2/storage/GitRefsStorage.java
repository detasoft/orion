package pro.deta.orion.git.parser.v2.storage;

/**
 * Implements ref reads and conditional updates internally behind GitStorageApi.
 * Callers outside this package access these operations only through GitStorageApi.
 * Expected old object IDs are checked at publication time, including for force updates and internal writes.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code snapshot()} - return a consistent snapshot of ref names and their object IDs.</li>
 *   <li>{@code defaultHead()} - return the configured default HEAD target.</li>
 *   <li>{@code updateAll(commands, atomic)} - apply ref/expected-old/new commands and return per-ref results.</li>
 * </ul>
 * Method names and signatures are provisional. With atomic updates, an expected-old mismatch prevents the
 * entire batch from being applied; otherwise commands may succeed independently. This does not imply crash
 * recovery for a partially persisted batch. Access and ancestry checks belong to the calling operation.
 */
final class GitRefsStorage {
}
