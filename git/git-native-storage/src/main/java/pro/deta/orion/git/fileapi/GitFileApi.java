package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * File operations borrowing a Git repository. Updates preserve omitted files and their modes;
 * preparing an update does not publish objects or move refs. An explicit null revision expects a new branch.
 * Local updates initialize an absent default branch; proxy updates leave unrelated refs unchanged.
 */
public final class GitFileApi {
    private final NativeGitRepository repository;
    private final boolean initializeDefaultHead;

    public GitFileApi(NativeGitRepository repository) {
        this(repository, true);
    }

    public GitFileApi(NativeGitRepository repository, boolean initializeDefaultHead) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.initializeDefaultHead = initializeDefaultHead;
    }

    public GitRepositoryFileSnapshot loadFiles(String branch, List<String> paths) throws GitOperationException {
        return new NativeRepositoryFileLoader(repository).loadFiles(branch, paths);
    }

    public void saveFiles(String branch, Map<String, GitFile> files, Set<String> deletedPaths,
                          String message, GitCommitAuthor author) throws GitOperationException {
        NativeGitFileUpdate update = prepareFileUpdate(branch, files, deletedPaths, message, author);
        GitOperationException.requireSuccess(repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL));
    }

    public NativeGitFileUpdate prepareFileUpdate(String branch, Map<String, GitFile> files,
                                                 Set<String> deletedPaths, String message, GitCommitAuthor author)
            throws GitOperationException {
        return new NativeRepositoryFileSaver(repository).prepareFiles(
                branch, files, deletedPaths, message, author, initializeDefaultHead);
    }

    public NativeGitFileUpdate prepareFileUpdate(String branch, String expectedRefRevision,
                                                 Map<String, GitFile> files, Set<String> deletedPaths,
                                                 String message, GitCommitAuthor author) throws GitOperationException {
        return new NativeRepositoryFileSaver(repository).prepareFiles(
                branch, expectedRefRevision, files, deletedPaths, message, author, initializeDefaultHead);
    }
}
