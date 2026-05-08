package pro.deta.orion.git.storage;

import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

public interface RepositoryStorage {
    boolean supports(RepositoryStorageLocator locator);

    Result<GitRepository> open(String repositoryName);
}
