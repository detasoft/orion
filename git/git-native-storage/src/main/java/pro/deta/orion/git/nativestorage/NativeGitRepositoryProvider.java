package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.util.Result;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.ReceivePackStatus;

import java.util.List;
import java.util.Map;

public interface NativeGitRepositoryProvider {
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

    default void saveFiles(
            String repositoryName,
            String refName,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        openForWrite(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName)
                .saveFiles(refName, files, message, author);
    }

    default NativeGitFileUpdate prepareFileUpdate(
            String repositoryName,
            String refName,
            String expectedRefRevision,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return openForWrite(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName)
                .prepareFileUpdate(refName, expectedRefRevision, files, message, author);
    }

    default List<ReceivePackStatus> publishPack(
            String repositoryName,
            byte[] pack,
            List<LooseRefStore.Update> updates,
            boolean atomic,
            GitNativeRepositoryAccessHook accessHook) throws GitOperationException {
        return openForWrite(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName)
                .publishPack(pack, updates, atomic, accessHook);
    }

    default List<RefUpdateResult> publish(
            NativeGitRepository repository,
            PackIngestionResult.Complete received,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        return repository.publishReceivedPack(received, updates, atomic);
    }
}
