package pro.deta.orion.git.nativestorage;

import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FileNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
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
        factory.close();
        if (failure != null) throw failure;
    }

    private final FileNativeGitRepositoryFactory factory;
    private final ConcurrentMap<String, NativeGitRepository> repositories = new ConcurrentHashMap<>();

    public FileNativeGitRepositoryProvider(Path rootDirectory) {
        factory = new FileNativeGitRepositoryFactory(rootDirectory);
    }

    @Override
    public synchronized List<String> repositoryNames() {
        requireOpen();
        return factory.repositoryNames();
    }

    @Override
    public synchronized boolean exists(String repositoryName) {
        requireOpen();
        return factory.exists(RepositoryName.parse(repositoryName));
    }

    @Override
    public synchronized Result<NativeGitRepository> find(String repositoryName) {
        requireOpen();
        RepositoryName name = RepositoryName.parse(repositoryName);
        if (!factory.exists(name)) {
            return new Result.Failure<>(
                    Result.FailureCode.NOT_FOUND,
                    "Native repository does not exist: " + name.value());
        }
        NativeGitRepository repository = repositories.get(name.value());
        if (repository != null) return new Result.Success<>(repository);
        return retain(name, factory.open(name));
    }

    @Override
    public synchronized Result<NativeGitRepository> create(String repositoryName) {
        requireOpen();
        RepositoryName name = RepositoryName.parse(repositoryName);
        return retain(name, factory.create(name));
    }

    private Result<NativeGitRepository> retain(RepositoryName name, Result<NativeGitRepository> result) {
        if (result instanceof Result.Success<NativeGitRepository> success) {
            NativeGitRepository previous = repositories.putIfAbsent(name.value(), success.value());
            if (previous != null) {
                success.value().close();
                return new Result.Success<>(previous);
            }
        }
        return result;
    }
}
