package pro.deta.orion.git.nativestorage;

import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;

import java.util.List;

/** Opens repositories without retaining them; ownership of successful results passes to the caller. */
public interface NativeGitRepositoryFactory extends AutoCloseable {
    List<String> repositoryNames();

    boolean exists(RepositoryName name);

    Result<NativeGitRepository> open(RepositoryName name);

    Result<NativeGitRepository> create(RepositoryName name);

    @Override
    void close();
}
