package pro.deta.orion.git.nativestorage;

import pro.deta.orion.util.Result;

import java.util.List;

public interface NativeGitRepositoryProvider {
    default List<String> repositoryNames() {
        return List.of();
    }

    boolean exists(String repositoryName);

    Result<NativeGitRepository> find(String repositoryName);

    Result<NativeGitRepository> create(String repositoryName);
}
