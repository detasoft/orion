package pro.deta.orion.git.parser.v2.storage;

/**
 * Implements ref reads and conditional updates internally behind GitStorageApi.
 * Callers outside this package access these operations only through GitStorageApi.
 * Expected old object IDs are checked at publication time, including for force updates and internal writes.
 * Acquire GitLock.lockRefs with all affected RefId values once for the entire update. Initially this locks
 * ref updates for the whole repository; pack and loose-object publication remain independent.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code snapshot()} - return a consistent snapshot of ref names and their object IDs.</li>
 *   <li>{@code defaultHead()} - return the configured default HEAD target.</li>
 *   <li>{@code updateAll(commands, atomic)} - apply ref/expected-old/new commands and return per-ref results.</li>
 * </ul>
 * Method names and signatures are provisional. Atomic updates publish the whole batch or none of it;
 * an expected-old mismatch aborts the batch and storage errors must not expose partially updated refs.
 * Previously published packs remain stored if ref updates fail. Non-atomic commands may succeed independently.
 * Access and ancestry checks belong to the calling operation. Crash durability is a separate backend guarantee.
 */
final class GitRefsStorage {
}
