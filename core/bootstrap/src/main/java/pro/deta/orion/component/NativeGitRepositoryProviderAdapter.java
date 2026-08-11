package pro.deta.orion.component;

import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryAdapter;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.util.Result;

import java.util.Objects;

final class NativeGitRepositoryProviderAdapter implements GitRepositoryProvider {
    private final NativeGitRepositoryProvider nativeRepositoryProvider;

    NativeGitRepositoryProviderAdapter(NativeGitRepositoryProvider nativeRepositoryProvider) {
        this.nativeRepositoryProvider = Objects.requireNonNull(
                nativeRepositoryProvider,
                "nativeRepositoryProvider");
    }

    @Override
    public boolean exists(String repositoryName) {
        return nativeRepositoryProvider.exists(nativeRepositoryName(repositoryName));
    }

    @Override
    public Result<GitRepository> find(String repositoryName) {
        return gitRepository(nativeRepositoryProvider.find(nativeRepositoryName(repositoryName)));
    }

    @Override
    public Result<GitRepository> findOrCreate(String repositoryName) {
        String nativeRepositoryName = nativeRepositoryName(repositoryName);
        if (nativeRepositoryProvider.exists(nativeRepositoryName)) {
            return find(nativeRepositoryName);
        }
        Result<NativeGitRepository> created = nativeRepositoryProvider.create(nativeRepositoryName);
        if (created instanceof Result.Failure<NativeGitRepository> failure
                && failure.code() == Result.FailureCode.FILE_ALREADY_EXISTS) {
            return find(nativeRepositoryName);
        }
        return gitRepository(created);
    }

    private static Result<GitRepository> gitRepository(Result<NativeGitRepository> result) {
        return switch (result) {
            case Result.Success<NativeGitRepository>(var repository) ->
                    new Result.Success<>(new NativeGitRepositoryAdapter(repository));
            case Result.Failure<NativeGitRepository> failure ->
                    new Result.Failure<>(failure);
        };
    }

    private static String nativeRepositoryName(String repositoryName) {
        Objects.requireNonNull(repositoryName, "repositoryName");
        if (repositoryName.isBlank()) {
            throw new IllegalArgumentException("repositoryName must not be blank");
        }
        if (repositoryName.startsWith("/")) {
            return repositoryName;
        }
        return "/" + repositoryName;
    }
}
