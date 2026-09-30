package pro.deta.orion.acl.storage;

import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.CheckedFunction;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class NativeGitAccessControlStorage implements AccessControlStorage {
    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String configurationRef;
    private final String path;
    private final boolean createIfMissing;

    NativeGitAccessControlStorage(
            ResolvedBootstrapSource source,
            NativeGitRepositoryProvider repositoryProvider) {
        Objects.requireNonNull(source, "source");
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        repositoryName = source.repositoryName().orElseThrow();
        configurationRef = source.refName();
        path = source.path();
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
            byte[] content = repository.files().readFile(new ObjectId(revision), path,
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
            return new Result.Success<>(new AccessControlSnapshot(Map.of(path, content), Optional.of(revision)));
        } catch (GitRepositoryFileNotFoundException error) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (IOException | GitOperationException | RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, String message, UserEmail author) {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] content = Objects.requireNonNull(snapshot.files().get(path), "configuration content");
        message = Objects.requireNonNullElse(message, "");
        author = Objects.requireNonNullElse(author, UserEmail.EMPTY);
        try {
            GitCommitAuthor commitAuthor = new GitCommitAuthor(author.getUsername(), author.getEmail());
            NativeGitRepository repository = repositoryProvider.openForWrite(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
            CheckedFunction<GitFileAccess, Void> update = access -> {
                byte[] previous = null;
                if (snapshot.version().isPresent()) {
                    try {
                        previous = repository.files().readFile(
                                new ObjectId(snapshot.version().orElseThrow()), path,
                                (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
                    } catch (GitRepositoryFileNotFoundException missing) {
                        // The configuration file has not been created at this revision yet.
                    }
                }
                if (!Arrays.equals(content, previous)) {
                    access.write(path, content);
                }
                access.apply();
                return null;
            };
            if (snapshot.version().isPresent()) {
                repository.files().withAccess(configurationRef, snapshot.version().orElseThrow(),
                        message, commitAuthor, update);
            } else {
                repository.files().withAccess(configurationRef, message, commitAuthor, update);
            }
        } catch (GitRefConflictException error) {
            throw new AccessControlConcurrentUpdateException("ACL configuration changed concurrently", error);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot save ACL to native repository " + repositoryName, error);
        }
    }

    @Override
    public String primaryPath() {
        return path;
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
