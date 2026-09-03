package pro.deta.orion.git.nativestorage;

import pro.deta.orion.util.Result;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.List;
import java.util.Map;

public interface NativeGitRepositoryProvider {
    default List<String> repositoryNames() {
        return List.of();
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

    default List<RefUpdateResult> publish(
            String repositoryName,
            LooseObjectStore objects,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        return openForWrite(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName)
                .publishObjectsAndRefs(objects, updates, atomic);
    }
}
