package pro.deta.orion.git.fileapi;

import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;

import java.util.Objects;
import java.util.UUID;

/** Identifies a file event source and the exact commit whose file content changed. */
public final class GitFileChanged extends OrionEvent {
    private final UUID source;
    private final CommitId revision;

    public GitFileChanged(UUID source, CommitId revision) {
        this.source = Objects.requireNonNull(source, "source");
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    public UUID source() {
        return source;
    }

    public CommitId revision() {
        return revision;
    }
}
