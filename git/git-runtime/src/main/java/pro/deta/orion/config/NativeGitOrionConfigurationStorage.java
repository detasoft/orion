package pro.deta.orion.config;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.util.Subscription;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.nativestorage.RepositoryRefChanged;
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
import java.util.Optional;

/**
 * Persists versioned documents in Git, keeping XML bytes behind the storage boundary.
 * Current loads refresh the configured repository; pinned loads read its retained local backing.
 * The application dispatcher owns event delivery, and close removes this storage's ref handler.
 */
@Slf4j
public final class NativeGitOrionConfigurationStorage
        implements OrionVersionedConfigurationStorage, AutoCloseable {
    private final OrionDocumentCodec codec = new OrionDocumentCodec();
    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String configurationRef;
    private final String path;
    private final Subscription repositorySubscription;
    private final OrionEventManager events;
    private final NativeGitRepository revisionRepository;

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
        NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName);
        this.revisionRepository = repository.eventSource();
        this.events = repository.events();
        this.repositorySubscription = events.subscribe(RepositoryRefChanged.class, this::onRefChanged);
    }

    @Override
    public OrionDocumentRevision load() {
        NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName);
        String revision = repository.refs().get(configurationRef);
        if (revision == null) {
            return new OrionDocumentRevision(
                    new Result.Failure<>(Result.FailureCode.NOT_FOUND),
                    new OrionRevision.Absent());
        }
        return load(repository, new OrionRevision.Stored(revision));
    }

    @Override
    public OrionDocumentRevision load(OrionRevision.Stored revision) {
        return load(revisionRepository, Objects.requireNonNull(revision, "revision"));
    }

    private OrionDocumentRevision load(
            NativeGitRepository repository, OrionRevision.Stored revision) {
        Result<OrionDocument> result;
        try {
            OrionDocument document = repository.files().readFile(new ObjectId(revision.value()), path,
                    (type, size, base, input) -> codec.read(input.newInputStream()));
            result = Result.of(document);
        } catch (GitRepositoryFileNotFoundException error) {
            result = new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (IOException | GitOperationException | RuntimeException error) {
            result = new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
        return new OrionDocumentRevision(result, revision);
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
            case OrionRevision.Absent ignored -> null;
            case OrionRevision.Stored stored -> stored.value();
        };
        try {
            GitCommitMetadata commitMetadata = new GitCommitMetadata(
                    new GitCommitAuthor(metadata.author().getUsername(), metadata.author().getEmail()),
                    metadata.message());
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
                OrionRevision savedRevision = storedRevision(
                        access.refUpdates().getFirst().newId().orElseThrow());
                access.apply();
                return savedRevision;
            };
            return repository.files().withAccess(configurationRef, revision, commitMetadata, update);
        } catch (GitRefConflictException error) {
            throw new OrionConfigurationConcurrentUpdateException("Orion configuration changed concurrently", error);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot save configuration to native repository " + repositoryName, error);
        }
    }

    private void onRefChanged(RepositoryRefChanged event) {
        if (event.repository() != revisionRepository
                || !configurationRef.equals(event.update().ref().value())) {
            return;
        }
        Optional<ObjectId> newCommit = event.update().newId();
        if (newCommit.isEmpty()) {
            log.warn("Configuration ref {} was deleted from repository {}; skipping notification",
                    configurationRef, repositoryName);
            return;
        }
        OrionRevision.Stored revision = storedRevision(newCommit.orElseThrow());
        OrionDocumentRevision snapshot = load(revision);
        switch (snapshot.result()) {
            case Result.Success<OrionDocument> ignored ->
                    events.publish(new ConfigurationChanged(this, snapshot));
            case Result.Failure<OrionDocument> failure -> {
                if (failure.code() == Result.FailureCode.NOT_FOUND) {
                    log.warn("Configuration file {} is missing in repository {} at {}; skipping notification",
                            path, repositoryName, revision.value());
                } else {
                    log.warn("Cannot read configuration {} in repository {} at {}; skipping notification",
                            path, repositoryName, revision.value(), failure.throwable());
                }
            }
        }
    }

    @Override
    public void close() {
        repositorySubscription.close();
    }

    private static OrionRevision.Stored storedRevision(ObjectId commitId) {
        return new OrionRevision.Stored(commitId.toHex());
    }
}
