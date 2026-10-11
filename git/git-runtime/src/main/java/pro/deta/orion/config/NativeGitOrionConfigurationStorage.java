package pro.deta.orion.config;

import pro.deta.orion.util.Subscription;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
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
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

import static pro.deta.orion.config.OrionRevision.INITIAL;

/** Persists versioned documents in Git, keeping XML bytes behind the storage boundary. */
public final class NativeGitOrionConfigurationStorage
        implements OrionVersionedConfigurationStorage {
    private final OrionDocumentCodec codec = new OrionDocumentCodec();
    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String configurationRef;
    private final String path;

    public NativeGitOrionConfigurationStorage(NativeGitRepositoryFactory repositoryFactory,
            BootstrapConfigurationSourceConfig source) {
        this(repositoryFactory.provider(),
                repositoryFactory.bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Orion configuration requires a resolved Git repository")),
                NativeGitRepositoryFactory.sourceRefName(source),
                NativeGitRepositoryFactory.repositoryPath(source.getPath()));
    }

    NativeGitOrionConfigurationStorage(
            NativeGitRepositoryProvider repositoryProvider,
            String repositoryName,
            String configurationRef,
            String path) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.configurationRef = Objects.requireNonNull(configurationRef, "configurationRef");
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public Result<OrionDocumentRevision> load() {
        try {
            NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
            return switch (findCurrentRevision(repository)) {
                case Result.Failure<OrionRevision.Stored> failure ->
                        new Result.Failure<>(failure.code(), failure.message(), failure.throwable());
                case Result.Success<OrionRevision.Stored> success -> load(repository, success.value());
            };
        } catch (RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    private Result<OrionRevision.Stored> findCurrentRevision(NativeGitRepository repository) {
        String currentRevision = repository.refs().get(configurationRef);
        if (currentRevision == null) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } else {
            return new Result.Success<>(new OrionRevision.Stored(currentRevision));
        }
    }

    @Override
    public Result<OrionDocumentRevision> load(OrionRevision.Stored revision) {
        Objects.requireNonNull(revision, "revision");
        try {
            NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
            return load(repository, revision);
        } catch (RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    private Result<OrionDocumentRevision> load(NativeGitRepository repository, OrionRevision.Stored revision) {
        try {
            OrionDocument document = repository.files().readFile(new ObjectId(revision.value()), path,
                    (type, size, base, input) -> codec.read(input.newInputStream()));
            return new Result.Success<>(new OrionDocumentRevision(document, revision));
        } catch (GitRepositoryFileNotFoundException error) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (IOException | GitOperationException | RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    private byte[] serialize(OrionDocument document) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            codec.write(document, output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot serialize configuration", failure);
        }
    }

    @Override
    public synchronized OrionRevision save(OrionRevision expectedRevision, OrionDocument document,
            ChangeMetadata metadata) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadata.author(), "author");
        byte[] content = serialize(document);
        String revision = switch (expectedRevision) {
            case OrionRevision.Initial initial -> null;
            case OrionRevision.Stored stored -> stored.value();
        };
        try {
            GitCommitAuthor commitAuthor = new GitCommitAuthor(
                    metadata.author().getUsername(), metadata.author().getEmail(), metadata.message());
            NativeGitRepository repository = repositoryProvider.openForWrite(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
            CheckedFunction<GitFileAccess, OrionRevision> update = access -> {
                byte[] previous = null;
                if (revision != null) {
                    try {
                        previous = repository.files().readFile(new ObjectId(revision), path,
                                (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
                    } catch (GitRepositoryFileNotFoundException missing) {
                        // The configuration file has not been created at this revision yet.
                    }
                }
                if (!Arrays.equals(content, previous)) {
                    access.write(path, content);
                }
                OrionRevision savedRevision = new OrionRevision.Stored(
                        access.refUpdates().getFirst().newId().orElseThrow().toHex());
                access.apply();
                return savedRevision;
            };
            return repository.files().withAccess(configurationRef, revision, commitAuthor, update);
        } catch (GitRefConflictException error) {
            throw new OrionConfigurationConcurrentUpdateException("Orion configuration changed concurrently", error);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot save configuration to native repository " + repositoryName, error);
        }
    }

    @Override
    public Subscription onChange(Consumer<OrionRevision> listener) {
        Objects.requireNonNull(listener, "listener");
        NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName);
        return repository.onRefUpdate(update -> {
            if (configurationRef.equals(update.update().ref().value())) {
                OrionRevision revision;
                if (update.update().newId().isPresent()) {
                    revision = new OrionRevision.Stored(update.update().newId().orElseThrow().toHex());
                } else {
                    revision = new OrionRevision.Initial();
                }
                listener.accept(revision);
            }
        });
    }
}
