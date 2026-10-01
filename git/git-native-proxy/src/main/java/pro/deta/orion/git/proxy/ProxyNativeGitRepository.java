package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.fileapi.GitFileApi;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncObservation;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** A remote Git repository backed by a local cache and the native Git client. */
final class ProxyNativeGitRepository extends NativeGitRepository {

    private volatile BootstrapGitLocation location;
    private final NativeGitRepository repository;
    private BootstrapGitTransportFactory transportFactory;
    private final BootstrapGitFetcher fetcher;
    private final BootstrapGitPusher pusher;
    private volatile AtomicReference<SyncObservation> observation =
            new AtomicReference<>(new SyncObservation(SyncStatus.NOT_CHECKED, null));
    private volatile boolean available = true;

    ProxyNativeGitRepository(
            String name,
            BootstrapGitLocation location,
            NativeGitRepository repository,
            BootstrapGitTransportFactory transportFactory,
            BootstrapGitFetcher fetcher,
            BootstrapGitPusher pusher) {
        super(name, repository.storage(), repository.index());
        this.location = Objects.requireNonNull(location, "location");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.pusher = Objects.requireNonNull(pusher, "pusher");
    }

    ProxyNativeGitRepository named(String name) {
        ProxyNativeGitRepository result = new ProxyNativeGitRepository(
                name, location, repository, transportFactory, fetcher, pusher);
        result.observation = observation;
        return result;
    }

    void reconfigure(ProxyNativeGitRepository replacement) {
        synchronized (repository) {
            location = replacement.location;
            transportFactory = replacement.transportFactory;
            observation = replacement.observation;
        }
    }

    void revoke() {
        synchronized (repository) {
            available = false;
        }
    }

    private void requireAvailable() {
        if (!available) throw new IllegalStateException("Proxy binding is unavailable");
    }

    SyncObservation syncObservation() {
        return observation.get();
    }

    BootstrapGitLocation location() {
        return location;
    }

    String repositoryName() {
        return repository.name();
    }

    private void observed(SyncStatus status) {
        observation.set(new SyncObservation(status, Instant.now()));
    }

    public void refresh() {
        synchronized (repository) {
            requireAvailable();
            try {
                transportFactory.withTransport(location, (selected, transport) -> {
                    fetcher.fetch(selected, transport, repository);
                    return null;
                });
                observed(SyncStatus.SUCCESS);
            } catch (BootstrapGitProxyException error) {
                observed(error.status());
                throw error;
            } catch (Exception error) {
                observed(SyncStatus.UNAVAILABLE);
                throw new BootstrapGitProxyException("upstream synchronization", error);
            }
        }
    }

    @Override
    public List<RefUpdateResult> publishReceivedPack(
            Optional<PackChecksum> received,
            List<RefUpdate> updates,
            boolean atomic) {
        synchronized (repository) {
            requireAvailable();
            try {
                List<RefUpdateResult> results = publishUpdates(received, updates, atomic);
                observed(hasConflict(results) ? SyncStatus.CONFLICT : SyncStatus.SUCCESS);
                return results;
            } catch (BootstrapGitProxyException error) {
                observed(error.status());
                throw error;
            } catch (RuntimeException error) {
                observed(SyncStatus.UNAVAILABLE);
                throw error;
            }
        }
    }

    @Override
    public GitStorageApi storage() {
        return repository().storage();
    }

    @Override
    public List<RefUpdateResult> publishRefs(List<RefUpdate> updates, boolean atomic) {
        return publishReceivedPack(Optional.empty(), updates, atomic);
    }

    @Override
    public GitFileApi files() {
        repository();
        return new GitFileApi(this, false);
    }

    @Override
    public String defaultHead() {
        return repository().defaultHead();
    }

    @Override
    public Map<String, String> refs() {
        return repository().refs();
    }

    @Override
    public RefUpdateResult updateRef(String refName, String expectedOldId, String newId) {
        return publishReceivedPack(
                Optional.empty(),
                List.of(RefUpdate.fromWire(refName, expectedOldId, newId)),
                true).getFirst();
    }

    @Override
    public RefUpdateSubscription onRefUpdate(Consumer<RefUpdateResult> listener) {
        return repository().onRefUpdate(listener);
    }

    @Override
    public ObjectId writeObject(GitObjectType type, byte[] data) {
        throw new UnsupportedOperationException("Proxy objects require a ref publication");
    }

    @Override
    public Optional<LooseObject> readObject(ObjectId id) {
        return repository().readObject(id);
    }

    @Override
    public List<RefUpdateResult> previewRefUpdates(
            List<RefUpdate> updates,
            boolean atomic) {
        return repository().previewRefUpdates(updates, atomic);
    }

    @Override
    public boolean hasCompleteObjectClosure(ObjectId root) {
        return repository().hasCompleteObjectClosure(root);
    }

    @Override
    public void close() {
    }

    private NativeGitRepository repository() {
        requireAvailable();
        return repository;
    }

    private List<RefUpdateResult> publishUpdates(
            Optional<PackChecksum> received,
            List<RefUpdate> updates,
            boolean atomic) {
        Objects.requireNonNull(received, "received");
        Objects.requireNonNull(updates, "updates");
        refresh();
        List<RefUpdateResult> preview = repository.previewRefUpdates(updates, atomic);
        if (atomic && hasConflict(preview)) {
            return preview;
        }
        List<RefUpdate> candidates = new ArrayList<>();
        List<Integer> candidateIndexes = new ArrayList<>();
        for (int index = 0; index < updates.size(); index++) {
            if (preview.get(index).status() == RefUpdateResult.Status.APPLIED) {
                validateClosure(updates.get(index));
                candidates.add(updates.get(index));
                candidateIndexes.add(index);
            }
        }
        if (candidates.isEmpty()) {
            return preview;
        }
        List<Boolean> accepted = push(received, candidates, atomic);
        if (accepted.size() != candidates.size()) {
            throw new BootstrapGitProxyException("upstream ref publication");
        }
        if (atomic && accepted.contains(false)) {
            return stale(updates);
        }
        List<RefUpdate> localUpdates = new ArrayList<>();
        List<Integer> localIndexes = new ArrayList<>();
        List<RefUpdateResult> results = new ArrayList<>(preview);
        for (int index = 0; index < candidates.size(); index++) {
            int originalIndex = candidateIndexes.get(index);
            if (accepted.get(index)) {
                localUpdates.add(candidates.get(index));
                localIndexes.add(originalIndex);
            } else {
                results.set(originalIndex, new RefUpdateResult(updates.get(originalIndex),
                        RefUpdateResult.Status.EXPECTED_OLD_MISMATCH, Optional.empty()));
            }
        }
        List<RefUpdateResult> localResults = repository.publishRefs(
                localUpdates,
                atomic);
        for (int index = 0; index < localResults.size(); index++) {
            results.set(localIndexes.get(index), localResults.get(index));
        }
        return List.copyOf(results);
    }

    private List<Boolean> push(
            Optional<PackChecksum> received,
            List<RefUpdate> updates,
            boolean atomic) {
        try {
            return transportFactory.withTransport(
                    location,
                    (selected, transport) -> pusher.push(selected, transport, repository, received, updates, atomic));
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (Exception error) {
            throw new BootstrapGitProxyException("upstream ref publication", error);
        }
    }

    private void validateClosure(
            RefUpdate update) {
        if (update.newId().isEmpty()) {
            return;
        }
        try {
            if (!repository.hasCompleteObjectClosure(update.newId().orElseThrow())) {
                throw new BootstrapGitProxyException("complete object validation");
            }
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BootstrapGitProxyException("complete object validation");
        }
    }

    private static List<RefUpdateResult> stale(List<RefUpdate> updates) {
        List<RefUpdateResult> results = new ArrayList<>(updates.size());
        for (RefUpdate update : updates) {
            results.add(new RefUpdateResult(update, RefUpdateResult.Status.EXPECTED_OLD_MISMATCH, Optional.empty()));
        }
        return List.copyOf(results);
    }
    private static boolean hasConflict(List<RefUpdateResult> results) {
        for (RefUpdateResult result : results) {
            if (result.status() == RefUpdateResult.Status.EXPECTED_OLD_MISMATCH) {
                return true;
            }
        }
        return false;
    }

}
