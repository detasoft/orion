package pro.deta.orion.git.storage;

import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.git.common.GitCommitAuthor;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.common.GitRepositoryFileSnapshot;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GitRepositoryProviderVersionedStorage implements VersionedStorage {
    private final GitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String branch;
    private final boolean createIfMissing;

    public GitRepositoryProviderVersionedStorage(GitRepositoryProvider repositoryProvider, String repositoryName, String branch) {
        this(repositoryProvider, repositoryName, branch, true);
    }

    public GitRepositoryProviderVersionedStorage(GitRepositoryProvider repositoryProvider, String repositoryName, String branch, boolean createIfMissing) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.branch = Objects.requireNonNull(branch, "branch");
        this.createIfMissing = createIfMissing;
    }

    @Override
    public Result<VersionedFileSnapshot> load(List<String> paths) {
        Result<GitRepository> result = repositoryProvider.find(repositoryName);
        if (result instanceof Result.Failure<GitRepository> failure) {
            return new Result.Failure<>(failure);
        }

        try (GitRepository repository = result.valueOrFailure("Git repository should load")) {
            GitRepositoryFileSnapshot snapshot = repository.loadFiles(branch, paths);
            return new Result.Success<>(new VersionedFileSnapshot(snapshot.files(), snapshot.version()));
        } catch (GitRepositoryFileNotFoundException e) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (IOException | GitOperationException | IllegalArgumentException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    @Override
    public void save(Map<String, byte[]> files, VersionedSaveRequest request) {
        Objects.requireNonNull(request, "request");
        Result<GitRepository> result = createIfMissing
                ? repositoryProvider.findOrCreate(repositoryName)
                : repositoryProvider.find(repositoryName);
        if (result instanceof Result.Failure<GitRepository> failure) {
            throw new RuntimeException("Cannot open git repository " + repositoryName + ": " + failureMessage(failure), failure.throwable());
        }

        try (GitRepository repository = result.valueOrFailure("Git repository should load")) {
            repository.saveFiles(branch, files, request.message(), gitAuthor(request.author()));
        } catch (IOException | GitOperationException e) {
            throw new RuntimeException("Cannot save files to git repository " + repositoryName, e);
        }
    }

    private static GitCommitAuthor gitAuthor(UserEmail author) {
        if (author == null) {
            return GitCommitAuthor.EMPTY;
        }
        return new GitCommitAuthor(author.getUsername(), author.getEmail());
    }

    private static String failureMessage(Result.Failure<?> failure) {
        if (failure.message() != null) {
            return failure.message();
        }
        if (failure.throwable() != null) {
            return failure.throwable().getMessage();
        }
        return failure.code().name();
    }
}
