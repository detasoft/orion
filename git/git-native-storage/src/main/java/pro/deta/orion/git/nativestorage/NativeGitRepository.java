package pro.deta.orion.git.nativestorage;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackObjectDirectory;
import pro.deta.orion.git.nativestorage.pack.PackPublicationStore;
import pro.deta.orion.git.nativestorage.pack.PublishedPackContent;
import pro.deta.orion.git.nativestorage.pack.PublishedPackManifest;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.GitUploadPackException;
import pro.deta.orion.git.nativestorage.upload.NativeFetchPackBuilder;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectClosure;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class NativeGitRepository implements AutoCloseable {
    private final String name;
    private final LooseRefStore looseRefStore;
    private final LooseObjectStore looseObjectStore;
    private final String defaultHead;
    private final PackPublicationStore packPublicationStore;
    private final PackObjectDirectory packObjectDirectory;
    private final CopyOnWriteArrayList<Consumer<RefUpdate>> refUpdateListeners =
            new CopyOnWriteArrayList<>();

    public NativeGitRepository(
            String name,
            LooseRefStore looseRefStore,
            LooseObjectStore looseObjectStore,
            String defaultHead) {
        this(
                name,
                looseRefStore,
                looseObjectStore,
                defaultHead,
                PackPublicationStore.NONE,
                PackObjectDirectory.NONE);
    }

    public NativeGitRepository(
            String name,
            LooseRefStore looseRefStore,
            LooseObjectStore looseObjectStore,
            String defaultHead,
            PackPublicationStore packPublicationStore) {
        this(
                name,
                looseRefStore,
                looseObjectStore,
                defaultHead,
                packPublicationStore,
                PackObjectDirectory.NONE);
    }

    public NativeGitRepository(
            String name,
            LooseRefStore looseRefStore,
            LooseObjectStore looseObjectStore,
            String defaultHead,
            PackPublicationStore packPublicationStore,
            PackObjectDirectory packObjectDirectory) {
        this.name = Objects.requireNonNull(name, "name");
        this.looseRefStore = Objects.requireNonNull(
                looseRefStore,
                "looseRefStore");
        this.looseObjectStore = Objects.requireNonNull(
                looseObjectStore,
                "looseObjectStore");
        this.defaultHead = Objects.requireNonNull(defaultHead, "defaultHead");
        this.packPublicationStore = Objects.requireNonNull(
                packPublicationStore,
                "packPublicationStore");
        this.packObjectDirectory = Objects.requireNonNull(
                packObjectDirectory,
                "packObjectDirectory");
    }

    public String name() {
        return name;
    }

    public String description() {
        return name;
    }

    public GitRepositoryFileSnapshot loadFiles(
            String branch,
            List<String> paths) throws GitOperationException {
        return new NativeRepositoryFileLoader(this).loadFiles(branch, paths);
    }

    public void saveFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        new NativeRepositoryFileSaver(this).saveFiles(branch, files, message, author);
    }

    public String defaultHead() {
        return defaultHead;
    }

    public Map<String, String> refs() {
        return looseRefStore.snapshot();
    }

    public RefUpdateResult updateRef(
            String refName,
            String expectedOldId,
            String newId) {
        RefUpdateResult result = looseRefStore.update(
                refName,
                expectedOldId,
                newId);
        notifyRefUpdate(new RefUpdate(refName, expectedOldId, newId, result));
        return result;
    }

    public RefUpdateSubscription onRefUpdate(Consumer<RefUpdate> listener) {
        Consumer<RefUpdate> registered = Objects.requireNonNull(listener, "listener");
        refUpdateListeners.add(registered);
        return () -> refUpdateListeners.remove(registered);
    }

    public GitObjectId writeObject(ObjectType type, byte[] data) {
        return looseObjectStore.write(type, data);
    }

    public Optional<LooseObject> readObject(GitObjectId id) {
        Optional<LooseObject> loose = looseObjectStore.read(id);
        if (loose.isPresent()) {
            return loose;
        }
        return packObjectDirectory.read(id);
    }

    public Optional<LooseObjectPrefix> readObjectPrefix(
            GitObjectId id,
            int maxDataBytes) {
        Optional<LooseObjectPrefix> loose =
                looseObjectStore.readPrefix(id, maxDataBytes);
        if (loose.isPresent()) {
            return loose;
        }
        return packObjectDirectory.readPrefix(id, maxDataBytes);
    }

    @Override
    public void close() {
    }

    public PackIngestionSession beginPackIngestion(
            PackIngestionLimits limits) {
        return new PackIngestor(
                Objects.requireNonNull(limits, "limits"),
                looseObjectStore,
                packPublicationStore);
    }

    public List<PublishedPackManifest> publishedPacks() {
        return packPublicationStore.publishedPacks();
    }

    public Optional<PublishedPackContent> openPublishedPack(
            String packId) {
        return packPublicationStore.openPublishedPack(packId);
    }

    public List<RefUpdateResult> publishObjectsAndRefs(
            LooseObjectStore quarantinedObjects,
            List<LooseRefStore.Update> updates) {
        return publishObjectsAndRefs(quarantinedObjects, updates, true);
    }

    public List<RefUpdateResult> publishObjectsAndRefs(
            LooseObjectStore quarantinedObjects,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        Objects.requireNonNull(quarantinedObjects, "quarantinedObjects");
        Objects.requireNonNull(updates, "updates");
        List<RefUpdateResult> results;
        if (!atomic) {
            results = looseRefStore.updateAllIndependently(
                    updates,
                    () -> looseObjectStore.putAll(quarantinedObjects));
        } else {
            results = looseRefStore.updateAll(
                    updates,
                    () -> looseObjectStore.putAll(quarantinedObjects));
        }
        notifyRefUpdates(updates, results, atomic);
        return results;
    }

    private void notifyRefUpdates(
            List<LooseRefStore.Update> updates,
            List<RefUpdateResult> results,
            boolean atomic) {
        if (atomic && results.contains(RefUpdateResult.STALE)) {
            return;
        }
        for (int index = 0; index < results.size(); index++) {
            LooseRefStore.Update update = updates.get(index);
            notifyRefUpdate(new RefUpdate(
                    update.refName(),
                    update.expectedOldId(),
                    update.newId(),
                    results.get(index)));
        }
    }

    private void notifyRefUpdate(RefUpdate update) {
        if (update.result() == RefUpdateResult.STALE
                || update.result() == RefUpdateResult.NO_OP) {
            return;
        }
        for (Consumer<RefUpdate> listener : refUpdateListeners) {
            try {
                listener.accept(update);
            } catch (RuntimeException error) {
                log.error(
                        "Native repository ref-update listener failed for {} {}",
                        name,
                        update.refName(),
                        error);
            }
        }
    }

    public boolean hasCompleteObjectClosure(
            GitObjectId root,
            LooseObjectStore quarantinedObjects) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(quarantinedObjects, "quarantinedObjects");
        NativeObjectClosure closure = new NativeObjectClosure(id ->
                quarantinedObjects.read(id).or(() -> readObject(id)));
        try {
            closure.objectIdsFor(Set.of(root), Set.of());
            return true;
        } catch (GitUploadPackException error) {
            if (error.kind() == GitUploadPackException.Kind.MISSING_OBJECT) {
                return false;
            }
            throw error;
        }
    }

    public NativePackProducer fetch(NativeFetchRequest request) {
        return fetchResponse(request).packProducer();
    }

    public NativeFetchResponse fetchResponse(NativeFetchRequest request) {
        return fetchResponse(request, NativePackfileUriSource.NONE);
    }

    public NativeFetchResponse fetchResponse(
            NativeFetchRequest request,
            NativePackfileUriSource packfileUriSource) {
        Objects.requireNonNull(request, "request");
        return new NativeFetchPackBuilder(
                looseRefStore,
                looseObjectStore,
                defaultHead,
                Objects.requireNonNull(
                        packfileUriSource,
                        "packfileUriSource"))
                .build(request);
    }

    public boolean legacyUploadReady(
            Iterable<GitObjectId> wants,
            Iterable<GitObjectId> commonHaves) {
        return new NativeObjectClosure(this::readObject)
                .allRootsReachAny(wants, commonHaves);
    }

    public record RefUpdate(
            String refName,
            String oldObjectId,
            String newObjectId,
            RefUpdateResult result) {
        public RefUpdate {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(oldObjectId, "oldObjectId");
            Objects.requireNonNull(newObjectId, "newObjectId");
            Objects.requireNonNull(result, "result");
        }
    }

    @FunctionalInterface
    public interface RefUpdateSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
