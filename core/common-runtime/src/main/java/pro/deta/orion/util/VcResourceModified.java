package pro.deta.orion.util;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

public class VcResourceModified extends OrionEvent {
    private final String oldRevision;
    private final String revision;

    public VcResourceModified(String oldRevision, String revision) {
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
