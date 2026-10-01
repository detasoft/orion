package pro.deta.orion.git.nativestorage;

import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.util.List;
import java.nio.file.Path;

/** Opens repositories without retaining them; ownership of successful results passes to the caller. */
public interface NativeGitRepositoryBackend extends AutoCloseable {
    static NativeGitRepositoryBackend file(Path rootDirectory) {
        return new FileNativeGitRepositoryFactory(rootDirectory);
    }

    static NativeGitRepositoryBackend inMemory() {
        return new InMemoryNativeGitRepositoryFactory();
    }
    List<String> repositoryNames();

    boolean exists(RepositoryName name);

    Result<NativeGitRepository> open(RepositoryName name);

    Result<NativeGitRepository> create(RepositoryName name);

    default NativeGitRepositoryBackend owner(RepositoryName name) {
        return this;
    }

    default Result<NativeGitRepository> reuse(RepositoryName name, NativeGitRepository repository) {
        return new Result.Success<>(repository);
    }

    default boolean visible(RepositoryName name) {
        return true;
    }

    default boolean retainedAvailable(RepositoryName name) {
        return true;
    }

    default boolean isPublicRepositoryName(String name) {
        return true;
    }

    @Override
    void close();
}
