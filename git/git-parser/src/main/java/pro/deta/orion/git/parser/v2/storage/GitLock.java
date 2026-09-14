package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Collection;

/**
 * Coordinates storage writes within one repository whose writes are owned by one JVM.
 * Shared by all writers of that repository; refs, packs, and loose objects use distinct lock namespaces.
 * This is an internal storage contract, not part of the external GitStorageApi.
 *
 * <p>lockRefs receives the nonempty collection of all ref names involved in an update, including a singleton
 * for a single ref. Initially every collection maps to one repository-wide refs lock, independently of its
 * names; the caller must not acquire it separately for each ref in a batch. Names remain part of the contract
 * so a future implementation can narrow locking without changing callers. Pack and object locks are per ID
 * and do not conflict with the refs lock or with one another's namespace.
 *
 * <p>The planned implementation uses a ConcurrentHashMap of internal keys to CompletableFuture signals.
 * Register a fresh signal with putIfAbsent. The winner owns publication; a competitor waits on the existing
 * signal with get() in its virtual thread, then retries registration. Storage I/O runs outside map operations.
 *
 * <p>After publication writes and failure cleanup finish, the owner removes its own mapping with
 * remove(key, signal) and completes the signal normally when its Lease closes, on success or failure.
 * Completion means ownership was released, not that publication succeeded. The caller must check published
 * state or expected-old refs after acquiring ownership. Cancelling a waiter must not cancel the shared signal
 * or its owner. Acquisition waits interruptibly; callers close the lease in all outcomes. No filesystem lock,
 * polling, sleeps, executor, or extra thread is needed.
 */
interface GitLock {
    Lease lockRefs(Collection<RefId> refs) throws InterruptedException;

    Lease lockPack(PackId packId) throws InterruptedException;

    Lease lockObject(ObjectId objectId) throws InterruptedException;

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
