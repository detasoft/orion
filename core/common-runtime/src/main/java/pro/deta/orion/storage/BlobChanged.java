package pro.deta.orion.storage;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

/** Reports the last explicitly loaded revision and an announced source revision for the subscriber to evaluate. */
public final class BlobChanged extends OrionEvent {
    private final String oldRevision;
    private final String revision;

    public BlobChanged(String oldRevision, String revision) {
        this.oldRevision = oldRevision;
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    public String oldRevision() {
        return oldRevision;
    }

    public String revision() {
        return revision;
    }
}
