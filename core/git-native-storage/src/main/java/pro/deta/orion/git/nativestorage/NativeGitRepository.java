package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitUploadRequest;
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
import pro.deta.orion.git.nativestorage.upload.NativeFetchPackBuilder;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public class NativeGitRepository implements GitRepository {
    private final String name;
    private final LooseRefStore looseRefStore;
    private final LooseObjectStore looseObjectStore;
    private final String defaultHead;
    private final PackPublicationStore packPublicationStore;
    private final PackObjectDirectory packObjectDirectory;

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

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return name;
    }

    @Override
    public GitRepository withFetchAccessCheck(
            Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        Objects.requireNonNull(fetchAccessCheck, "fetchAccessCheck");
        return this;
    }

    @Override
    public void upload(
            GitUploadRequest request,
            InputStream input,
            OutputStream output,
            OutputStream error) throws GitOperationException {
        throw new GitOperationException("Native repository upload is not supported yet");
    }

    @Override
    public void receive(
            GitReceiveRequest request,
            InputStream input,
            OutputStream output,
            OutputStream error) throws GitOperationException {
        throw new GitOperationException("Native repository receive is not supported yet");
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
        return looseRefStore.update(
                refName,
                expectedOldId,
                newId);
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
    public <T> Optional<T> unwrap(Class<T> repositoryType) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (repositoryType.isInstance(this)) {
            return Optional.of(repositoryType.cast(this));
        }
        return Optional.empty();
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
        Objects.requireNonNull(quarantinedObjects, "quarantinedObjects");
        Objects.requireNonNull(updates, "updates");
        return looseRefStore.updateAll(
                updates,
                () -> looseObjectStore.putAll(quarantinedObjects));
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
                Objects.requireNonNull(
                        packfileUriSource,
                        "packfileUriSource"))
                .build(request);
    }
}
