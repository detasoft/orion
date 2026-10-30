package pro.deta.orion.git.fileapi;

import pro.deta.orion.util.Subscription;
import pro.deta.orion.util.EventListeners;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.BlobChanged;
import pro.deta.orion.storage.BlobStorageConflictException;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Stores opaque bytes in a Git file without retaining content. Explicit reads remember the loaded revision.
 * Source notifications report that revision and the new source revision without loading or accepting it.
 */
public final class GitBlobStorage implements BlobStorage {
    private final GitFileEvents events;
    private final Subscription changes;
    private final EventListeners<BlobChanged> subscribers = new EventListeners<>();
    private final GitCommitMetadata metadata;
    private volatile String loadedRevision;

    public GitBlobStorage(GitFileEvents events, GitCommitMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.events = Objects.requireNonNull(events, "events");
        changes = events.events().subscribe(GitFileChanged.class, event -> {
            if (events.matches(event)) {
                subscribers.publish(new BlobChanged(
                        loadedRevision, event.revision().toHex()));
            }
        });
    }

    @Override
    public Subscription subscribe(Consumer<BlobChanged> subscriber) {
        return subscribers.subscribe(subscriber);
    }

    @Override
    public Content load() throws IOException {
        String revision;
        try {
            revision = events.file().revision().map(CommitId::toHex).orElse(null);
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read blob revision", failure);
        }
        if (revision == null) {
            loadedRevision = null;
            return new Content(null, null);
        }
        try {
            return new Content(revision, read(revision));
        } catch (FileNotFoundException missing) {
            loadedRevision = revision;
            return new Content(revision, null);
        }
    }

    @Override
    public byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        try {
            GitFile file = events.file();
            CommitId requested = new CommitId(revision);
            byte[] bytes = file.read(requested);
            loadedRevision = revision;
            return bytes;
        } catch (GitRepositoryFileNotFoundException missing) {
            FileNotFoundException failure = new FileNotFoundException("Blob is absent at " + revision);
            failure.initCause(missing);
            throw failure;
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot read blob store at " + revision, failure);
        }
    }

    @Override
    public String write(Modification modification) throws IOException {
        byte[] bytes = modification.bytes();
        String expectedRevision = modification.expectedRevision();
        Objects.requireNonNull(bytes, "bytes");
        try {
            GitFile file = events.file();
            CommitId expected = expectedRevision == null ? null : new CommitId(expectedRevision);
            CommitId saved = file.write(expected, bytes, metadata);
            return saved.toHex();
        } catch (GitRefConflictException failure) {
            throw conflict();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot write blob store", failure);
        }
    }

    private static BlobStorageConflictException conflict() {
        return new BlobStorageConflictException("Blob store changed before save");
    }

    @Override
    public void close() {
        try {
            changes.close();
        } finally {
            subscribers.close();
            events.close();
        }
    }
}
