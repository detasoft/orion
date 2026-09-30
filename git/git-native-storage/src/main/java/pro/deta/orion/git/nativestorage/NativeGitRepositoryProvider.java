package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.id.PackChecksum;
import java.util.Optional;
import pro.deta.orion.util.Result;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns opened repositories and releases them when the provider is closed. */
public interface NativeGitRepositoryProvider extends AutoCloseable {
    @Override
    void close();

    default List<String> repositoryNames() {
        return List.of();
    }

    default boolean isPublicRepositoryName(String repositoryName) {
        return true;
    }

    boolean exists(String repositoryName);

    Result<NativeGitRepository> find(String repositoryName);

    Result<NativeGitRepository> create(String repositoryName);

    default Result<NativeGitRepository> openForRead(String repositoryName) {
        return find(repositoryName);
    }

    default Result<NativeGitRepository> openForWrite(String repositoryName) {
        return find(repositoryName);
    }

    default List<RefUpdateResult> publishPack(
            String repositoryName,
            byte[] pack,
            List<RefUpdate> updates,
            boolean atomic,
            GitNativeRepositoryAccessHook accessHook) throws GitOperationException {
        return openForWrite(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName)
                .publishPack(pack, updates, atomic, accessHook);
    }

    default List<RefUpdateResult> publish(
            NativeGitRepository repository,
            Optional<PackChecksum> received,
            List<RefUpdate> updates,
            boolean atomic) {
        return repository.publishReceivedPack(received, updates, atomic);
    }
}
