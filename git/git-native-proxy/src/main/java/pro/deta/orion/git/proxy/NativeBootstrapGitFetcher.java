package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.pack.PackIngestionOutput;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

final class NativeBootstrapGitFetcher implements BootstrapGitFetcher {
    private static final String NULL_ID = "0".repeat(40);
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();
    @Override
    public void fetch(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(repository, "repository");
        GitUploadPackClient client = new GitUploadPackClient(transport);
        GitRemoteAdvertisement advertisement = success(
                client.discover(location.remoteUri(), OPTIONS), "upstream discovery");
        GitRemoteAdvertisement.Ref remoteRef = findRef(advertisement, location.refName());
        String oldId = repository.refs().getOrDefault(location.refName(), NULL_ID);
        if (oldId.equals(remoteRef.objectId())) {
            return;
        }
        ObjectId target = new ObjectId(remoteRef.objectId());
        if (!hasCompleteObjectClosure(repository, target)) {
            fetchPack(client, location, repository, oldId, remoteRef.objectId());
            if (!hasCompleteObjectClosure(repository, target)) {
                throw new BootstrapGitProxyException("complete object validation");
            }
        }
        RefUpdate update = RefUpdate.fromWire(location.refName(), oldId, remoteRef.objectId());
        RefUpdateResult result = repository.publishRefs(List.of(update), true).getFirst();
        if (result.status() != RefUpdateResult.Status.APPLIED) {
            throw new BootstrapGitProxyException("local ref publication",
                    result.status() == RefUpdateResult.Status.EXPECTED_OLD_MISMATCH
                            ? ProxyAwareNativeGitRepositoryProvider.SyncStatus.CONFLICT
                            : ProxyAwareNativeGitRepositoryProvider.SyncStatus.UNAVAILABLE);
        }
    }

    private static boolean hasCompleteObjectClosure(NativeGitRepository repository, ObjectId target) {
        try {
            return repository.hasCompleteObjectClosure(target);
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BootstrapGitProxyException("complete object validation");
        }
    }

    private static GitRemoteAdvertisement.Ref findRef(
            GitRemoteAdvertisement advertisement,
            String refName) {
        for (GitRemoteAdvertisement.Ref candidate : advertisement.refs()) {
            if (refName.equals(candidate.name())) {
                return candidate;
            }
        }
        throw new BootstrapGitProxyException("required ref lookup");
    }

    private static void fetchPack(
            GitUploadPackClient client,
            BootstrapGitLocation location,
            NativeGitRepository repository,
            String oldId,
            String newId) {
        try (PackIngestionOutput output = new PackIngestionOutput(repository.storage())) {
            GitUploadPackRequest request = new GitUploadPackRequest(
                    List.of(newId),
                    NULL_ID.equals(oldId) ? List.of() : List.of(oldId),
                    output,
                    ignored -> { });
            success(client.fetch(location.remoteUri(), OPTIONS, request), "pack transfer");
            repository.storage().persist(output.complete());
        } catch (IOException failure) {
            throw new BootstrapGitProxyException("pack validation");
        }
    }

    private static <T> T success(GitClientResult<T> result, String stage) {
        if (result instanceof GitClientResult.Success<T> success) {
            return success.value();
        }
        throw new BootstrapGitProxyException(stage, ((GitClientResult.Failed<T>) result).failure());
    }

}
