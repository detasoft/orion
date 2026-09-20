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
        if (repository.hasCompleteObjectClosure(
                new ObjectId(remoteRef.objectId()))) {
            NativeFetchedRefPublisher.publish(
                    repository,
                    RefUpdate.fromWire(location.refName(), oldId, remoteRef.objectId()));
            return;
        }
        fetchPack(client, location, repository, oldId, remoteRef.objectId());
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
            NativeFetchedRefPublisher.publish(
                    repository,
                    RefUpdate.fromWire(location.refName(), oldId, newId));
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
