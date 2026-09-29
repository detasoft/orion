package pro.deta.orion.git.proxy;

import pro.deta.orion.git.fileapi.GitFileApi;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

final class PolicyBoundNativeGitRepository extends NativeGitRepository {
    private final ProxyAwareNativeGitRepositoryProvider provider;
    private final String repositoryName;
    private final NativeGitRepository repository;

    PolicyBoundNativeGitRepository(
            ProxyAwareNativeGitRepositoryProvider provider,
            String repositoryName,
            NativeGitRepository repository) {
        super(repositoryName, repository.storage(), repository.index(), repository.defaultHead());
        this.provider = provider;
        this.repositoryName = repositoryName;
        this.repository = repository;
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
        return provider.requireBinding(repositoryName, repository.name()).publish(
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
    public List<RefUpdateResult> publishReceivedPack(
            Optional<PackChecksum> received,
            List<RefUpdate> updates,
            boolean atomic) {
        return provider.requireBinding(repositoryName, repository.name()).publish(received, updates, atomic);
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
        provider.requireBinding(repositoryName, repository.name());
        return repository;
    }
}
