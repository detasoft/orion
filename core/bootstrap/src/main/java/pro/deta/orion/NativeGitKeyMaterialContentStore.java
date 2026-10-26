package pro.deta.orion;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitCommitMetadata;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.fileapi.GitFileEvents;
import pro.deta.orion.git.fileapi.GitFileChanged;
import pro.deta.orion.util.Subscription;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.keymaterial.KeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialStoreConflictException;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts a Git file to KMS and warns when external changes require reopening the material.
 * Tracks accepted revisions and in-flight writes so own publications do not cause restart warnings.
 * Persistence and event publication run without holding this object's state monitor.
 */
@Slf4j
final class NativeGitKeyMaterialContentStore implements KeyMaterialContentStore {
    private final GitFileEvents events;
    private final Subscription changes;
    private CommitId activeRevision;
    private int pendingWrites;

    NativeGitKeyMaterialContentStore(GitFileEvents events) {
        this.events = Objects.requireNonNull(events, "events");
        changes = events.events().subscribe(GitFileChanged.class, event -> {
            if (events.matches(event)) onFileChanged(events.file());
        });
    }

    private void onFileChanged(GitFile file) {
        CommitId accepted;
        synchronized (this) {
            if (pendingWrites != 0) return;
            accepted = activeRevision;
        }
        if (accepted == null) return;
        try {
            boolean current = file.isCurrent(accepted);
            synchronized (this) {
                if (pendingWrites == 0 && Objects.equals(accepted, activeRevision) && !current) {
                    log.warn("KMS file changed in the tracked branch; restart Orion to load the new material");
                }
            }
        } catch (GitOperationException | RuntimeException failure) {
            log.warn("Cannot inspect KMS file; restart Orion before using externally changed material", failure);
        }
    }

    @Override
    public Optional<String> revision() throws IOException {
        try {
            GitFile file = events.file();
            Optional<CommitId> revision = file.revision();
            if (revision.isEmpty() || !file.exists(revision.orElseThrow())) return Optional.empty();
            return Optional.of(revision.orElseThrow().toHex());
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot read key material revision", failure);
        }
    }

    @Override
    public byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        try {
            GitFile file = events.file();
            CommitId requested = new CommitId(revision);
            byte[] bytes = file.read(requested);
            synchronized (this) {
                if (activeRevision == null && pendingWrites == 0) activeRevision = requested;
            }
            onFileChanged(file);
            return bytes;
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot read key material store at " + revision, failure);
        }
    }

    @Override
    public String write(byte[] bytes, String expectedRevision) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        synchronized (this) {
            pendingWrites++;
        }
        try {
            GitFile file = events.file();
            CommitId expected = expectedRevision == null ? null : new CommitId(expectedRevision);
            CommitId saved = file.write(expected, bytes,
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, "Update server identity material"));
            synchronized (this) {
                activeRevision = saved;
            }
            return saved.toHex();
        } catch (GitRefConflictException failure) {
            throw conflict();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot write key material store", failure);
        } finally {
            synchronized (this) {
                pendingWrites--;
            }
            onFileChanged(events.file());
        }
    }

    private static KeyMaterialStoreConflictException conflict() {
        return new KeyMaterialStoreConflictException("Key material store changed before save");
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
