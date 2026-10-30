package pro.deta.orion;

import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitCommitMetadata;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.fileapi.GitFileEvents;
import pro.deta.orion.git.fileapi.GitFileChanged;
import pro.deta.orion.util.Subscription;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.keymaterial.KeyMaterialContentSource;
import pro.deta.orion.keymaterial.KeyMaterialSourceChanged;
import pro.deta.orion.keymaterial.KeyMaterialStoreConflictException;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.Objects;

/** Bridges a Git file to a material source and reports committed file changes without retaining content. */
final class NativeGitKeyMaterialContentSource implements KeyMaterialContentSource {
    private final GitFileEvents events;
    private final Subscription changes;

    NativeGitKeyMaterialContentSource(GitFileEvents events) {
        this.events = Objects.requireNonNull(events, "events");
        changes = events.events().subscribe(GitFileChanged.class, event -> {
            if (events.matches(event)) {
                events.events().publish(new KeyMaterialSourceChanged(this, event.revision().toHex()));
            }
        });
    }

    @Override
    public Content load() throws IOException {
        String revision;
        try {
            revision = events.file().revision().map(CommitId::toHex).orElse(null);
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read key material revision", failure);
        }
        if (revision == null) return new Content(null, null);
        try {
            return new Content(revision, read(revision));
        } catch (FileNotFoundException missing) {
            return new Content(revision, null);
        }
    }

    @Override
    public byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        try {
            GitFile file = events.file();
            CommitId requested = new CommitId(revision);
            return file.read(requested);
        } catch (GitRepositoryFileNotFoundException missing) {
            FileNotFoundException failure = new FileNotFoundException("Key material is absent at " + revision);
            failure.initCause(missing);
            throw failure;
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot read key material store at " + revision, failure);
        }
    }

    @Override
    public String write(byte[] bytes, String expectedRevision) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        try {
            GitFile file = events.file();
            CommitId expected = expectedRevision == null ? null : new CommitId(expectedRevision);
            CommitId saved = file.write(expected, bytes,
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, "Update server identity material"));
            return saved.toHex();
        } catch (GitRefConflictException failure) {
            throw conflict();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot write key material store", failure);
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
