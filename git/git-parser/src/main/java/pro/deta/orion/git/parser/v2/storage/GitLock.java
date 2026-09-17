package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Collection;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

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
 * <p>The implementation uses a ConcurrentHashMap of internal keys to CompletableFuture signals.
 * Register a fresh signal with putIfAbsent. The winner owns publication; a competitor waits on the existing
 * signal with get() in the calling thread, then retries registration. Storage I/O runs outside map operations.
 *
 * <p>After publication writes and failure cleanup finish, the owner removes its own mapping with
 * remove(key, signal) and completes the signal normally when its Lease closes, on success or failure.
 * Completion means ownership was released, not that publication succeeded. The caller must check published
 * state or expected-old refs after acquiring ownership. Cancelling a waiter must not cancel the shared signal
 * or its owner. Acquisition waits interruptibly; callers close the lease in all outcomes. No filesystem lock,
 * polling, sleeps, executor, or extra thread is needed. Active keys include the canonical repository path,
 * so distinct facade instances share ownership without retaining idle repositories in a registry.
 */
final class GitLock {
    private static final ConcurrentHashMap<Key, CompletableFuture<Void>> OWNERS = new ConcurrentHashMap<>();
    private final Path repository;

    GitLock(Path canonicalRepository) {
        repository = canonicalRepository;
    }

    Lease lockRefs(Collection<RefId> refs) throws InterruptedException {
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("Ref lock requires at least one ref");
        }
        return acquire("refs");
    }

    Lease lockPack(PackId packId) throws InterruptedException {
        return acquire(packId);
    }

    Lease lockObject(ObjectId objectId) throws InterruptedException {
        return acquire(objectId);
    }

    private Lease acquire(Object identity) throws InterruptedException {
        var key = new Key(repository, identity);
        var signal = new CompletableFuture<Void>();
        for (;;) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while acquiring repository lock");
            }
            var owner = OWNERS.putIfAbsent(key, signal);
            if (owner == null) {
                return () -> {
                    OWNERS.remove(key, signal);
                    signal.complete(null);
                };
            }
            try {
                owner.get();
            } catch (ExecutionException impossible) {
                throw new IllegalStateException("Lock release signal failed", impossible);
            }
        }
    }

    private record Key(Path repository, Object identity) { }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
