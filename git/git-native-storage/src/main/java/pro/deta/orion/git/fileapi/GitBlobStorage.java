package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

public final class GitBlobStorage extends BlobStorage implements AutoCloseable, Consumer<GitFileChanged> {
    private final GitFileEvents fileEvents;

    public GitBlobStorage(GitFileEvents events) {
        super(events.events(), currentRevision(events.file()));
        this.fileEvents = events;
        events.events().registry().subscribe(GitFileChanged.class, events.source(), this);
    }

    private static VcRevision currentRevision(GitFile file) {
        return file.revision().<VcRevision>map(id -> new VcRevision.Stored(id.toHex()))
                .orElseGet(VcRevision.Absent::new);
    }

    @Override
    public void accept(GitFileChanged event) {
        if (!getRevision().equals(new VcRevision.Stored(event.revision().toHex()))) {
            applyRevision(currentRevision(fileEvents.file()));
        }
    }

    @Override
    protected BlobStorageReadOperations createReadOperation(VcRevision.ReadTarget target) {
        VcRevision selected = target instanceof VcRevision.Stored stored ? stored : getRevision();
        if (!(selected instanceof VcRevision.Stored stored)) {
            throw new UncheckedIOException(new FileNotFoundException("Git blob is absent"));
        }
        CommitId revision = new CommitId(stored.value());
        return () -> {
            try {
                return fileEvents.file().read(revision);
            } catch (GitRepositoryFileNotFoundException missing) {
                FileNotFoundException failure = new FileNotFoundException("Blob is absent at " + stored.value());
                failure.initCause(missing);
                throw new UncheckedIOException(failure);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } catch (GitOperationException failure) {
                throw new IllegalStateException("Cannot read Git blob", failure);
            }
        };
    }

    @Override
    protected BlobStorageUpdateOperations createUpdateOperation() {
        GitFileApi.VcModification modification = fileEvents.file().withModification();
        return new BlobStorageUpdateOperations() {
            @Override
            public void write(byte[] content) {
                modification.addChange(access -> {
                    try {
                        access.write(fileEvents.file().path(), content);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            }

            @Override
            protected VcRevision commit(VcChangeDescription description) {
                return modification.commit(description);
            }

            @Override
            protected void discard() {
                modification.discard();
            }
        };
    }

    @Override
    public void close() {
        fileEvents.events().registry().unsubscribe(fileEvents.source(), this);
        fileEvents.close();
    }
}
