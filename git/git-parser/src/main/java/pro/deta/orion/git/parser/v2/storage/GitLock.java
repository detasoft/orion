package pro.deta.orion.git.parser.v2.storage;

/**
 * Coordinates publication ownership by packId within one repository whose writes are owned by one JVM.
 * Shared by all publishers of that repository; different pack IDs proceed independently. This is an internal
 * storage contract, not part of the external GitStorageApi. Method signatures remain to be defined.
 *
 * <p>The planned implementation uses a ConcurrentHashMap of pack IDs to CompletableFuture completion signals.
 * Register a fresh signal with putIfAbsent. The winner owns publication; a competitor waits on the existing
 * signal with get() in its virtual thread, then retries registration. Storage I/O runs outside map operations.
 *
 * <p>After publication writes and failure cleanup finish, the owner removes its own mapping with
 * remove(packId, signal) and completes the signal normally in a finally block, on success or failure.
 * Completion means ownership was released, not that publication succeeded. The caller must check published
 * state after acquiring ownership. Cancelling a waiter must not cancel the shared signal or its owner.
 * No filesystem lock, repository-wide publication lock, polling, sleeps, executor, or extra thread is needed.
 */
interface GitLock {
}
