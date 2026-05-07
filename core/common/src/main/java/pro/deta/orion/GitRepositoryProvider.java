package pro.deta.orion;

import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;


public interface GitRepositoryProvider {
    boolean exists(String repositoryName);

    Result<GitRepository> find(String repositoryName);

    Result<GitRepository> findOrCreate(String repositoryName);
}
