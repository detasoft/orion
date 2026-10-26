package pro.deta.orion.config;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.fileapi.GitFileEvents;
import pro.deta.orion.git.fileapi.GitFileChanged;
import pro.deta.orion.util.Subscription;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
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
    private final GitFileEvents events;
    private final Subscription changes;

    public NativeGitOrionConfigurationStorage(GitFileEvents events) {
        this.events = Objects.requireNonNull(events, "events");
        changes = events.events().subscribe(GitFileChanged.class, event -> {
            if (events.matches(event)) {
                onFileChanged(events.file(), new OrionRevision.Stored(event.revision().toHex()));
            }
        });
    }

    @Override
    public OrionDocumentRevision load() {
        Optional<CommitId> revision = events.file().revision();
        if (revision.isEmpty()) {
            return new OrionDocumentRevision(
                    new Result.Failure<>(Result.FailureCode.NOT_FOUND), new OrionRevision.Absent());
        }
        return load(new OrionRevision.Stored(revision.orElseThrow().toHex()));
    }

    @Override
    public OrionDocumentRevision load(OrionRevision.Stored revision) {
        return load(events.file(), revision);
    }

    private OrionDocumentRevision load(GitFile file, OrionRevision.Stored revision) {
        Objects.requireNonNull(revision, "revision");
        Result<OrionDocument> result;
        try {
            byte[] bytes = file.read(new CommitId(revision.value()));
            try {
                result = Result.of(codec.read(new ByteArrayInputStream(bytes)));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
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
    public OrionRevision.Stored save(OrionRevision expectedRevision, OrionDocument document,
            ChangeMetadata metadata) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadata.author(), "author");
        byte[] content = serialize(document);
        CommitId revision = switch (expectedRevision) {
            case OrionRevision.Absent ignored -> null;
            case OrionRevision.Stored stored -> new CommitId(stored.value());
        };
        try {
            GitCommitMetadata commitMetadata = new GitCommitMetadata(
                    new GitCommitAuthor(metadata.author().getUsername(), metadata.author().getEmail()),
                    metadata.message());
            return new OrionRevision.Stored(events.file().write(revision, content, commitMetadata).toHex());
        } catch (GitRefConflictException error) {
            throw new OrionConfigurationConcurrentUpdateException("Orion configuration changed concurrently", error);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot save configuration to Git file", error);
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private void onFileChanged(GitFile file, OrionRevision.Stored revision) {
        OrionDocumentRevision snapshot = load(file, revision);
        switch (snapshot.result()) {
            case Result.Success<OrionDocument> ignored ->
                    file.events().publish(new ConfigurationChanged(this, snapshot));
            case Result.Failure<OrionDocument> failure -> {
                if (failure.code() == Result.FailureCode.NOT_FOUND) {
                    log.warn("Configuration file is missing at {}; skipping notification", revision.value());
                } else {
                    log.warn("Cannot read configuration at {}; skipping notification",
                            revision.value(), failure.throwable());
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            changes.close();
        } finally {
            events.close();
        }
    }
}
