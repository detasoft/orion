package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BootstrapGitRuntimeProxy implements RuntimeGitProxyBinding {
    private static final String NULL_ID = "0".repeat(40);

    private final BootstrapGitLocation location;
    private final NativeGitRepository repository;
    private final BootstrapGitTransportFactory transportFactory;
    private final BootstrapGitFetcher fetcher;
    private final BootstrapGitPusher pusher;

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

    @Override
    public synchronized void refresh() {
        try {
            transportFactory.withTransport(location, transport -> {
                fetcher.fetch(location, transport, repository);
                return null;
            });
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (Exception error) {
            throw new BootstrapGitProxyException("upstream synchronization");
        }
    }

    @Override
    public synchronized List<RefUpdateResult> publish(
            LooseObjectStore objects,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(updates, "updates");
        refresh();
        List<RefUpdateResult> preview = repository.previewRefUpdates(updates, atomic);
        if (atomic && preview.contains(RefUpdateResult.STALE)) {
            return preview;
        }
        List<LooseRefStore.Update> candidates = new ArrayList<>();
        List<Integer> candidateIndexes = new ArrayList<>();
        for (int index = 0; index < updates.size(); index++) {
            if (preview.get(index) != RefUpdateResult.STALE) {
                validateClosure(objects, updates.get(index));
                candidates.add(updates.get(index));
                candidateIndexes.add(index);
            }
        }
        if (candidates.isEmpty()) {
            return preview;
        }
        repository.publishObjects(objects);
        List<Boolean> accepted = push(candidates, atomic);
        if (accepted.size() != candidates.size()) {
            throw new BootstrapGitProxyException("upstream ref publication");
        }
        if (atomic && accepted.contains(false)) {
            return stale(updates.size());
        }
        List<LooseRefStore.Update> localUpdates = new ArrayList<>();
        List<Integer> localIndexes = new ArrayList<>();
        List<RefUpdateResult> results = new ArrayList<>(preview);
        for (int index = 0; index < candidates.size(); index++) {
            int originalIndex = candidateIndexes.get(index);
            if (accepted.get(index)) {
                localUpdates.add(candidates.get(index));
                localIndexes.add(originalIndex);
            } else {
                results.set(originalIndex, RefUpdateResult.STALE);
            }
        }
        List<RefUpdateResult> localResults = repository.publishObjectsAndRefs(
                new LooseObjectStore(),
                localUpdates,
                atomic);
        for (int index = 0; index < localResults.size(); index++) {
            results.set(localIndexes.get(index), localResults.get(index));
        }
        return List.copyOf(results);
    }

    private List<Boolean> push(
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        try {
            return transportFactory.withTransport(
                    location,
                    transport -> pusher.push(location, transport, repository, updates, atomic));
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (Exception error) {
            throw new BootstrapGitProxyException("upstream ref publication");
        }
    }

    private void validateClosure(
            LooseObjectStore objects,
            LooseRefStore.Update update) {
        if (NULL_ID.equals(update.newId())) {
            return;
        }
        try {
            if (!repository.hasCompleteObjectClosure(GitObjectId.of(update.newId()), objects)) {
                throw new BootstrapGitProxyException("complete object validation");
            }
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BootstrapGitProxyException("complete object validation");
        }
    }

    private static List<RefUpdateResult> stale(int size) {
        List<RefUpdateResult> results = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            results.add(RefUpdateResult.STALE);
        }
        return List.copyOf(results);
    }
}
