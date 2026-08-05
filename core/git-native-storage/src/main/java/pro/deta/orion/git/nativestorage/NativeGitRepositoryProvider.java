package pro.deta.orion.git.nativestorage;

import pro.deta.orion.util.Result;

public interface NativeGitRepositoryProvider {
    boolean exists(String repositoryName);

    Result<NativeGitRepository> find(String repositoryName);

    Result<NativeGitRepository> findOrCreate(String repositoryName);
}
