package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncObservation;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BootstrapGitRuntimeProxy {

    private final BootstrapGitLocation location;
    private final NativeGitRepository repository;
    private final BootstrapGitTransportFactory transportFactory;
    private final BootstrapGitFetcher fetcher;
    private final BootstrapGitPusher pusher;
    private volatile SyncObservation observation = new SyncObservation(SyncStatus.NOT_CHECKED, null);

    BootstrapGitRuntimeProxy(
            BootstrapGitLocation location,
            NativeGitRepository repository,
            BootstrapGitTransportFactory transportFactory,
            BootstrapGitFetcher fetcher,
            BootstrapGitPusher pusher) {
        this.location = Objects.requireNonNull(location, "location");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.pusher = Objects.requireNonNull(pusher, "pusher");
    }

    SyncObservation syncObservation() {
        return observation;
    }

    BootstrapGitLocation location() {
        return location;
    }

    String repositoryName() {
        return repository.name();
    }

    private void observed(SyncStatus status) {
        observation = new SyncObservation(status, Instant.now());
    }

    public synchronized void refresh() {
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
            throw new BootstrapGitProxyException("upstream synchronization");
        }
    }

    public synchronized List<RefUpdateResult> publish(
            Optional<PackId> received,
            List<RefUpdate> updates,
            boolean atomic) {
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

    private List<RefUpdateResult> publishUpdates(
            Optional<PackId> received,
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
            Optional<PackId> received,
            List<RefUpdate> updates,
            boolean atomic) {
        try {
            return transportFactory.withTransport(
                    location,
                    (selected, transport) -> pusher.push(selected, transport, repository, received, updates, atomic));
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (Exception error) {
            throw new BootstrapGitProxyException("upstream ref publication");
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
