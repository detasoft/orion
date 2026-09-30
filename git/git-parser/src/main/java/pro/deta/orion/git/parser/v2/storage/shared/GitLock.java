package pro.deta.orion.git.parser.v2.storage.shared;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Serializes index operations for a repository owned by one JVM. Canonical repository paths share
 * ownership across facade instances without retaining idle repositories. Acquisition is interruptible;
 * cancelling a waiter does not release the owner. Closing a lease signals release, not success.
 * Callers recheck indexed state after acquiring ownership and close the lease on every outcome.
 */
public final class GitLock {
    private static final ConcurrentHashMap<Object, CompletableFuture<Void>> OWNERS = new ConcurrentHashMap<>();
    private final Object repository;

    public GitLock(Object canonicalRepository) {
        repository = canonicalRepository;
    }

    public Lease lock() throws InterruptedException {
        CompletableFuture<Void> signal = new CompletableFuture<>();
        for (;;) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while acquiring repository lock");
            }
            CompletableFuture<Void> owner = OWNERS.putIfAbsent(repository, signal);
            if (owner == null) {
                return () -> {
                    OWNERS.remove(repository, signal);
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

    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
