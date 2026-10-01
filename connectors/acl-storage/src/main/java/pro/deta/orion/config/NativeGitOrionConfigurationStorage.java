package pro.deta.orion.config;

import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.internal.CheckedFunction;
import pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class NativeGitOrionConfigurationStorage implements OrionConfigurationStorage {
    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String configurationRef;
    private final String path;
    private final boolean createIfMissing;

    public NativeGitOrionConfigurationStorage(NativeGitRepositoryFactory repositoryFactory,
            BootstrapConfigurationSourceConfig source) {
        this(repositoryFactory.provider(),
                repositoryFactory.bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Orion configuration requires a resolved Git repository")),
                NativeGitRepositoryFactory.sourceRefName(source),
                NativeGitRepositoryFactory.repositoryPath(source.getPath()), source.isCreateDefaultIfMissing());
    }

    NativeGitOrionConfigurationStorage(
            NativeGitRepositoryProvider repositoryProvider,
            String repositoryName,
            String configurationRef,
            String path,
            boolean createIfMissing) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.configurationRef = Objects.requireNonNull(configurationRef, "configurationRef");
        this.path = Objects.requireNonNull(path, "path");
        this.createIfMissing = createIfMissing;
    }

    @Override
    public Result<ConfigurationFile> load() {
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
            return new Result.Success<>(new ConfigurationFile(content, Optional.of(revision)));
        } catch (GitRepositoryFileNotFoundException error) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (IOException | GitOperationException | RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    @Override
    public void save(ConfigurationFile file, String message, UserEmail author) {
        Objects.requireNonNull(file, "configuration file");
        byte[] content = file.content();
        message = Objects.requireNonNullElse(message, "");
        author = Objects.requireNonNullElse(author, UserEmail.EMPTY);
        try {
            GitCommitAuthor commitAuthor = new GitCommitAuthor(author.getUsername(), author.getEmail());
            NativeGitRepository repository = repositoryProvider.openForWrite(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
            CheckedFunction<GitFileAccess, Void> update = access -> {
                byte[] previous = null;
                if (file.revision().isPresent()) {
                    try {
                        previous = repository.files().readFile(
                                new ObjectId(file.revision().orElseThrow()), path,
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
            if (file.revision().isPresent()) {
                repository.files().withAccess(configurationRef, file.revision().orElseThrow(),
                        message, commitAuthor, update);
            } else {
                repository.files().withAccess(configurationRef, message, commitAuthor, update);
            }
        } catch (GitRefConflictException error) {
            throw new OrionConfigurationConcurrentUpdateException("Orion configuration changed concurrently", error);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot save configuration to native repository " + repositoryName, error);
        }
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
