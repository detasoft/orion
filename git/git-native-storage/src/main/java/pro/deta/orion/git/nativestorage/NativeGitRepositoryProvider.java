package pro.deta.orion.git.nativestorage;

import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Owns every repository returned by its factory and closes them together. */
public class NativeGitRepositoryProvider implements AutoCloseable {
    private final NativeGitRepositoryBackend factory;
    private final ConcurrentMap<RepositoryKey, RepositoryEntry> repositories = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean closed;

    protected NativeGitRepositoryProvider() {
        this(NativeGitRepositoryBackend.inMemory());
    }

    public NativeGitRepositoryProvider(NativeGitRepositoryBackend factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public static NativeGitRepositoryProvider file(Path rootDirectory) {
        return new NativeGitRepositoryProvider(new FileNativeGitRepositoryFactory(rootDirectory));
    }

    public static NativeGitRepositoryProvider inMemory() {
        return new NativeGitRepositoryProvider(new InMemoryNativeGitRepositoryFactory());
    }

    public List<String> repositoryNames() {
        return operation(() -> {
            TreeSet<String> names = new TreeSet<>(factory.repositoryNames());
            for (Map.Entry<RepositoryKey, RepositoryEntry> entry : repositories.entrySet()) {
                RepositoryName name = RepositoryName.parse(entry.getKey().name());
                if (entry.getValue().view != null && factory.visible(name)
                        && entry.getKey().owner() == factory.owner(name)) {
                    names.add(name.value());
                }
            }
            return List.copyOf(names);
        });
    }

    public boolean isPublicRepositoryName(String repositoryName) {
        return factory.isPublicRepositoryName(repositoryName);
    }

    public boolean exists(String repositoryName) {
        return operation(() -> {
            RepositoryName name = RepositoryName.parse(repositoryName);
            RepositoryEntry entry = entryIfPresent(name);
            return (entry != null && entry.view != null && factory.retainedAvailable(name))
                    || factory.exists(name);
        });
    }

    public Result<NativeGitRepository> find(String repositoryName) {
        return operation(() -> {
            RepositoryName name = RepositoryName.parse(repositoryName);
            RepositoryEntry entry;
            try {
                entry = entry(name);
            } catch (RuntimeException failure) {
                return new Result.Failure<>(Result.FailureCode.GENERAL,
                        "Cannot resolve repository factory", failure);
            }
            try {
                synchronized (entry) {
                    if (entry.view != null && factory.retainedAvailable(name)) {
                        return entry.key.owner().reuse(name, entry.view);
                    }
                    return retain(entry, entry.key.owner().open(name));
                }
            } finally {
                release(entry);
            }
        });
    }

    public Result<NativeGitRepository> create(String repositoryName) {
        return operation(() -> {
            RepositoryName name = RepositoryName.parse(repositoryName);
            RepositoryEntry entry;
            try {
                entry = entry(name);
            } catch (RuntimeException failure) {
                return new Result.Failure<>(Result.FailureCode.GENERAL,
                        "Cannot resolve repository factory", failure);
            }
            try {
                synchronized (entry) {
                    if (entry.view != null && factory.retainedAvailable(name)) {
                        return new Result.Failure<>(Result.FailureCode.FILE_ALREADY_EXISTS,
                                "Native repository already exists: " + name.value());
                    }
                    return retain(entry, entry.key.owner().create(name));
                }
            } finally {
                release(entry);
            }
        });
    }

    private Result<NativeGitRepository> retain(RepositoryEntry entry, Result<NativeGitRepository> result) {
        if (result instanceof Result.Success<NativeGitRepository> success) {
            if (entry.view != null) {
                success.value().close();
                return new Result.Success<>(entry.view);
            }
            entry.view = success.value();
            if (entry.backing == null) entry.backing = success.value();
        }
        return result;
    }

    public Result<NativeGitRepository> openForRead(String repositoryName) {
        return find(repositoryName);
    }

    public Result<NativeGitRepository> openForWrite(String repositoryName) {
        return find(repositoryName);
    }

    public List<RefUpdateResult> publishPack(String repositoryName, byte[] pack,
            List<RefUpdate> updates, boolean atomic, GitNativeRepositoryAccessHook accessHook)
            throws GitOperationException {
        return openForWrite(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName)
                .publishPack(pack, updates, atomic, accessHook);
    }

    public List<RefUpdateResult> publish(NativeGitRepository repository, Optional<PackChecksum> received,
            List<RefUpdate> updates, boolean atomic) {
        return repository.publishReceivedPack(received, updates, atomic);
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            for (RepositoryEntry entry : repositories.values()) {
                try {
                    if (entry.view != null && entry.view != entry.backing) entry.view.close();
                    if (entry.backing != null) entry.backing.close();
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            repositories.clear();
            try {
                factory.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Repository provider is closed");
    }

    public NativeGitRepository openBacking(String repositoryName, NativeGitRepositoryBackend backing) {
        return operation(() -> {
            RepositoryName name = RepositoryName.parse(repositoryName);
            RepositoryEntry entry = entry(name);
            try {
                synchronized (entry) {
                    if (entry.backing != null) return entry.backing;
                    Result<NativeGitRepository> opened = backing.open(name);
                    if (opened instanceof Result.Failure<NativeGitRepository> failure
                            && failure.code() == Result.FailureCode.NOT_FOUND) {
                        opened = backing.create(name);
                        if (opened instanceof Result.Failure<NativeGitRepository> raced
                                && raced.code() == Result.FailureCode.FILE_ALREADY_EXISTS) {
                            opened = backing.open(name);
                        }
                    }
                    NativeGitRepository repository = opened.valueOrFailure("Cannot open Git proxy cache");
                    entry.backing = repository;
                    if (entry.view == null) entry.view = repository;
                    return repository;
                }
            } finally {
                release(entry);
            }
        });
    }

    public boolean backingExists(String repositoryName) {
        return operation(() -> {
            RepositoryEntry entry = entryIfPresent(RepositoryName.parse(repositoryName));
            return entry != null && entry.backing != null;
        });
    }

    public void bind(String repositoryName, NativeGitRepository view) {
        operation(() -> {
            RepositoryName name = RepositoryName.parse(repositoryName);
            RepositoryEntry entry = entry(name);
            try {
                synchronized (entry) {
                    entry.view = Objects.requireNonNull(view, "repository view");
                }
            } finally {
                release(entry);
            }
            return null;
        });
    }

    public void unbind(String repositoryName) {
        operation(() -> {
            RepositoryName name = RepositoryName.parse(repositoryName);
            RepositoryEntry entry = entry(name);
            try {
                synchronized (entry) {
                    entry.view = null;
                }
            } finally {
                release(entry);
            }
            return null;
        });
    }

    private RepositoryEntry entry(RepositoryName name) {
        RepositoryKey key = new RepositoryKey(name.value(), factory.owner(name));
        return repositories.compute(key, (ignored, current) -> {
            RepositoryEntry entry = current == null ? new RepositoryEntry(key) : current;
            entry.users++;
            return entry;
        });
    }

    private void release(RepositoryEntry entry) {
        repositories.computeIfPresent(entry.key, (ignored, current) -> {
            if (current != entry) throw new IllegalStateException("Repository entry changed during an operation");
            current.users--;
            return current.users == 0 && current.backing == null && current.view == null ? null : current;
        });
    }

    private RepositoryEntry entryIfPresent(RepositoryName name) {
        return repositories.get(new RepositoryKey(name.value(), factory.owner(name)));
    }

    private <T> T operation(Supplier<T> action) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            return action.get();
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private static final class RepositoryEntry {
        private final RepositoryKey key;
        private int users;
        private volatile NativeGitRepository backing;
        private volatile NativeGitRepository view;

        private RepositoryEntry(RepositoryKey key) {
            this.key = key;
        }
    }

    private record RepositoryKey(String name, NativeGitRepositoryBackend owner) {}
}
