package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    private boolean closed;

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Repository provider is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (NativeGitRepository repository : repositories.values()) {
            try {
                repository.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        repositories.clear();
        if (failure != null) throw failure;
    }

    private static final String DEFAULT_HEAD = "refs/heads/main";

    private final ConcurrentMap<String, NativeGitRepository> repositories = new ConcurrentHashMap<>();

    @Override
    public synchronized List<String> repositoryNames() {
        requireOpen();
        List<String> names = new ArrayList<>(repositories.keySet());
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    @Override
    public synchronized boolean exists(String repositoryName) {
        requireOpen();
        return repositories.containsKey(requireName(repositoryName));
    }

    @Override
    public synchronized Result<NativeGitRepository> find(String repositoryName) {
        requireOpen();
        String name = requireName(repositoryName);
        NativeGitRepository repository = repositories.get(name);
        if (repository == null) {
            return new Result.Failure<>(
                    Result.FailureCode.NOT_FOUND,
                    "Native repository does not exist: " + name);
        }
        return new Result.Success<>(repository);
    }

    @Override
    public synchronized Result<NativeGitRepository> create(String repositoryName) {
        requireOpen();
        String name = requireName(repositoryName);
        InMemoryStorage storage = new InMemoryStorage();
        NativeGitRepository repository = new NativeGitRepository(
                name, storage, new InMemoryIndex(), DEFAULT_HEAD);
        NativeGitRepository previous = repositories.putIfAbsent(
                name,
                repository);
        if (previous != null) {
            repository.close();
            return new Result.Failure<>(
                    Result.FailureCode.FILE_ALREADY_EXISTS,
                    "Native repository already exists: " + name);
        }
        return new Result.Success<>(repository);
    }

    private static String requireName(String repositoryName) {
        return RepositoryName.parse(repositoryName).value();
    }
}
