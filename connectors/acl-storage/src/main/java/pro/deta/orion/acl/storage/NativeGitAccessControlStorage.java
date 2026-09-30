package pro.deta.orion.acl.storage;

import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.CheckedFunction;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class NativeGitAccessControlStorage implements AccessControlStorage {
    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String configurationRef;
    private final List<String> paths;
    private final boolean createIfMissing;

    NativeGitAccessControlStorage(
            ResolvedBootstrapSource source,
            NativeGitRepositoryProvider repositoryProvider) {
        Objects.requireNonNull(source, "source");
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        repositoryName = source.repositoryName().orElseThrow();
        configurationRef = source.refName();
        paths = source.paths();
        createIfMissing = source.createIfMissing();
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        NativeGitRepository repository;
        try {
            repository = repositoryProvider.openForRead(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
        } catch (RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
        try {
            String revision = repository.refs().get(configurationRef);
            if (revision == null) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String path : paths) {
                files.put(path, repository.files().readFile(new ObjectId(revision), path,
                        (type, size, base, input) -> input.readBytes(Math.toIntExact(size))));
            }
            return new Result.Success<>(new AccessControlSnapshot(files, Optional.of(revision)));
        } catch (GitRepositoryFileNotFoundException error) {
            if (primaryPathIsMissing(repository)) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        } catch (IOException | GitOperationException | RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        try {
            GitCommitAuthor author = author(request);
            NativeGitRepository repository = repositoryProvider.openForWrite(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
            CheckedFunction<GitFileAccess, Void> update = access -> {
                for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                    byte[] content = entry.getValue();
                    if (snapshot.version().isPresent()) {
                        byte[] previous;
                        try {
                            previous = repository.files().readFile(
                                    new ObjectId(snapshot.version().orElseThrow()), entry.getKey(),
                                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
                        } catch (GitRepositoryFileNotFoundException missing) {
                            previous = null;
                        }
                        if (Arrays.equals(content, previous)) {
                            continue;
                        }
                    }
                    try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
                        access.write(entry.getKey(), FileMode.REGULAR_FILE, content.length, input);
                    }
                }
                access.apply();
                return null;
            };
            if (snapshot.version().isPresent()) {
                repository.files().withAccess(configurationRef, snapshot.version().orElseThrow(),
                        request.message(), author, update);
            } else {
                repository.files().withAccess(configurationRef, request.message(), author, update);
            }
        } catch (GitRefConflictException error) {
            throw new AccessControlConcurrentUpdateException("ACL configuration changed concurrently", error);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot save ACL to native repository " + repositoryName, error);
        }
    }

    private static GitCommitAuthor author(AccessControlSaveRequest request) {
        return new GitCommitAuthor(request.author().getUsername(), request.author().getEmail());
    }

    private boolean primaryPathIsMissing(NativeGitRepository repository) {
        if (paths.size() == 1) {
            return true;
        }
        try {
            String revision = repository.refs().get(configurationRef);
            if (revision == null) {
                return true;
            }
            repository.files().readFile(new ObjectId(revision), paths.getFirst(),
                    (type, size, base, input) -> Boolean.TRUE);
            return false;
        } catch (GitRepositoryFileNotFoundException missing) {
            return true;
        } catch (IOException | GitOperationException failure) {
            return false;
        }
    }

    @Override
    public String primaryPath() {
        return paths.getFirst();
    }

    @Override
    public boolean createIfMissing() {
        return createIfMissing;
    }

    @Override
    public ChangeSubscription onChange(Consumer<String> listener) {
        Consumer<String> registered = Objects.requireNonNull(listener, "listener");
        NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName);
        NativeGitRepository.RefUpdateSubscription subscription = repository.onRefUpdate(update -> {
            if (configurationRef.equals(update.update().ref().value())) {
                registered.accept("native repository " + repository.name() + " ref " + configurationRef);
            }
        });
        return subscription::close;
    }
}
