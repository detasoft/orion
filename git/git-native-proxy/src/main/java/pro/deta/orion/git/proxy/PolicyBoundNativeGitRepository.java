package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PublishedPackContent;
import pro.deta.orion.git.nativestorage.pack.PublishedPackManifest;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

final class PolicyBoundNativeGitRepository extends NativeGitRepository {
    private final ProxyAwareNativeGitRepositoryProvider provider;
    private final String repositoryName;
    private final NativeGitRepository repository;

    PolicyBoundNativeGitRepository(
            ProxyAwareNativeGitRepositoryProvider provider,
            NativeGitRepository repository) {
        super(repository.name(), new LooseRefStore(), new LooseObjectStore(), repository.defaultHead());
        this.provider = provider;
        this.repositoryName = repository.name();
        this.repository = repository;
    }

    @Override
    public String description() {
        return repository().description();
    }

    @Override
    public GitRepositoryFileSnapshot loadFiles(String branch, List<String> paths)
            throws GitOperationException {
        return repository().loadFiles(branch, paths);
    }

    @Override
    public void saveFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        provider.saveFiles(repositoryName, branch, files, message, author);
    }

    @Override
    public NativeGitFileUpdate prepareFileUpdate(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return repository().prepareProxyFileUpdate(branch, files, message, author);
    }

    @Override
    public NativeGitFileUpdate prepareFileUpdate(
            String branch,
            String expectedRefRevision,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return repository().prepareProxyFileUpdate(
                branch, expectedRefRevision, files, message, author);
    }

    @Override
    public NativeGitFileUpdate prepareProxyFileUpdate(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return repository().prepareProxyFileUpdate(branch, files, message, author);
    }

    @Override
    public NativeGitFileUpdate prepareProxyFileUpdate(
            String branch,
            String expectedRefRevision,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return repository().prepareProxyFileUpdate(
                branch, expectedRefRevision, files, message, author);
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
        return provider.publish(
                repositoryName,
                new LooseObjectStore(),
                List.of(new LooseRefStore.Update(refName, expectedOldId, newId)),
                true).getFirst();
    }

    @Override
    public RefUpdateSubscription onRefUpdate(Consumer<RefUpdate> listener) {
        return repository().onRefUpdate(listener);
    }

    @Override
    public GitObjectId writeObject(ObjectType type, byte[] data) {
        throw new UnsupportedOperationException("Proxy objects require a ref publication");
    }

    @Override
    public Optional<LooseObject> readObject(GitObjectId id) {
        return repository().readObject(id);
    }

    @Override
    public Optional<LooseObjectPrefix> readObjectPrefix(GitObjectId id, int maxDataBytes) {
        return repository().readObjectPrefix(id, maxDataBytes);
    }

    @Override
    public PackIngestionSession beginPackIngestion(PackIngestionLimits limits) {
        return repository().beginPackIngestion(limits);
    }

    @Override
    public List<PublishedPackManifest> publishedPacks() {
        return repository().publishedPacks();
    }

    @Override
    public Optional<PublishedPackContent> openPublishedPack(String packId) {
        return repository().openPublishedPack(packId);
    }

    @Override
    public List<RefUpdateResult> publishObjectsAndRefs(
            LooseObjectStore objects,
            List<LooseRefStore.Update> updates) {
        return provider.publish(repositoryName, objects, updates, true);
    }

    @Override
    public List<RefUpdateResult> publishObjectsAndRefs(
            LooseObjectStore objects,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        return provider.publish(repositoryName, objects, updates, atomic);
    }

    @Override
    public List<RefUpdateResult> previewRefUpdates(
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        return repository().previewRefUpdates(updates, atomic);
    }

    @Override
    public void publishObjects(LooseObjectStore objects) {
        throw new UnsupportedOperationException("Proxy objects require a ref publication");
    }

    @Override
    public boolean hasCompleteObjectClosure(GitObjectId root, LooseObjectStore quarantinedObjects) {
        return repository().hasCompleteObjectClosure(root, quarantinedObjects);
    }

    @Override
    public NativePackProducer fetch(NativeFetchRequest request) {
        return repository().fetch(request);
    }

    @Override
    public NativeFetchResponse fetchResponse(NativeFetchRequest request) {
        return repository().fetchResponse(request);
    }

    @Override
    public NativeFetchResponse fetchResponse(
            NativeFetchRequest request,
            NativePackfileUriSource packfileUriSource) {
        return repository().fetchResponse(request, packfileUriSource);
    }

    @Override
    public boolean legacyUploadReady(
            Iterable<GitObjectId> wants,
            Iterable<GitObjectId> commonHaves) {
        return repository().legacyUploadReady(wants, commonHaves);
    }

    @Override
    public void close() {
    }

    private NativeGitRepository repository() {
        return repository;
    }
}
