package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.util.Result;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryNativeGitRepositoryProvider {
    private static final String DEFAULT_HEAD = "refs/heads/main";

    private final ConcurrentMap<String, NativeGitRepository> repositories =
            new ConcurrentHashMap<>();

    public boolean exists(String repositoryName) {
        return repositories.containsKey(requireName(repositoryName));
    }

    public Result<NativeGitRepository> find(String repositoryName) {
        String name = requireName(repositoryName);
        NativeGitRepository repository = repositories.get(name);
        if (repository == null) {
            return new Result.Failure<>(
                    Result.FailureCode.NOT_FOUND,
                    "Native repository does not exist: " + name);
        }
        return new Result.Success<>(repository);
    }

    public Result<NativeGitRepository> findOrCreate(String repositoryName) {
        String name = requireName(repositoryName);
        NativeGitRepository repository = repositories.computeIfAbsent(
                name,
                ignored -> new NativeGitRepository(
                        name,
                        new LooseRefStore(),
                        new LooseObjectStore(),
                        DEFAULT_HEAD));
        return new Result.Success<>(repository);
    }

    private static String requireName(String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException(
                    "repositoryName must not be blank");
        }
        return repositoryName;
    }
}
