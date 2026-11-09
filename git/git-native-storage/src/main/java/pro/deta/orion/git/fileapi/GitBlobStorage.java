package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

public final class GitBlobStorage extends BlobStorage implements AutoCloseable {
    private final GitFileEvents fileEvents;

    public GitBlobStorage(GitFileEvents events) {
        super(events.events());
        this.fileEvents = events;
        this.events.subscribe(GitFileChanged.class, events.source(), this::accept);
    }

    private VcRevision currentRevision() {
        return fileEvents.file().revision().<VcRevision>map(id -> new VcRevision.Stored(id.toHex()))
                .orElseGet(VcRevision.Absent::new);
    }

    private void accept(GitFileChanged event) {
        VcRevision oldRevision = event.oldRevision()
                .<VcRevision>map(id -> new VcRevision.Stored(id.toHex())).orElseGet(VcRevision.Absent::new);
        publishChange(oldRevision, new VcRevision.Stored(event.revision().toHex()));
    }

    @Override
    protected BlobStorageOperations createOperation(VcRevision.ReadTarget target) {
        VcRevision base = switch (target) {
            case VcRevision.Latest ignored -> currentRevision();
            case VcRevision.Stored stored -> stored;
            case VcRevision.Absent absent -> absent;
        };
        if (base instanceof VcRevision.Absent absent) {
            VcRevision current = currentRevision();
            if (!absent.equals(current)) throw new VcModificationException(absent, current);
        }
        byte[] initial = readSnapshot(base);
        return new BlobStorageOperations(base) {
            private byte[] content = initial;

            @Override
            public byte[] readContent() {
                if (content == null) throw new UncheckedIOException(new FileNotFoundException("Git blob is absent"));
                return Arrays.copyOf(content, content.length);
            }

            @Override
            public void write(byte[] bytes) {
                byte[] copy = Arrays.copyOf(bytes, bytes.length);
                if (content != null && content != initial) Arrays.fill(content, (byte) 0);
                content = copy;
            }

            @Override
            protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                if (content == initial && revision() instanceof VcRevision.Stored stored) return stored;
                if (content == null) throw new IllegalStateException("No blob content prepared");
                CommitId expected = revision() instanceof VcRevision.Stored stored ? new CommitId(stored.value()) : null;
                try {
                    return new VcRevision.Stored(fileEvents.file().write(expected, content, description).toHex());
                } catch (GitRefConflictException failure) {
                    VcRevision actual = failure.actual().<VcRevision>map(id -> new VcRevision.Stored(id.toHex()))
                            .orElseGet(VcRevision.Absent::new);
                    VcModificationException conflict = new VcModificationException(revision(), actual);
                    conflict.initCause(failure);
                    throw conflict;
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                } catch (RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot commit Git blob", failure);
                }
            }

            @Override
            public void close() {
                if (initial != null) Arrays.fill(initial, (byte) 0);
                if (content != null && content != initial) Arrays.fill(content, (byte) 0);
            }
        };
    }

    private byte[] readSnapshot(VcRevision revision) {
        if (!(revision instanceof VcRevision.Stored stored)) return null;
        try {
            return fileEvents.file().read(new CommitId(stored.value()));
        } catch (GitRepositoryFileNotFoundException missing) {
            return null;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (GitOperationException failure) {
            throw new IllegalStateException("Cannot read Git blob", failure);
        }
    }

    @Override
    public void close() {
        events.unsubscribe(GitFileChanged.class, fileEvents.source());
        fileEvents.close();
    }
}
