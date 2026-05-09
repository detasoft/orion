package pro.deta.orion.git.s3;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

@Singleton
public class S3GitRepositoryProvider implements GitRepositoryProvider {
    private static final String NOT_IMPLEMENTED = "S3 repository storage is not implemented yet";

    @Inject
    public S3GitRepositoryProvider() {
    }

    @Override
    public boolean exists(String repositoryName) {
        return false;
    }

    @Override
    public Result<GitRepository> find(String repositoryName) {
        return notSupported();
    }

    @Override
    public Result<GitRepository> findOrCreate(String repositoryName) {
        return notSupported();
    }

    private Result<GitRepository> notSupported() {
        return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, NOT_IMPLEMENTED);
    }
}
