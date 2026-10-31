package pro.deta.orion.util;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

public final class VcResourceModified extends OrionEvent {
    private final VcResource target;
    private final VcRevision oldRevision;
    private final VcRevision revision;

    public VcResourceModified(VcResource target, VcRevision oldRevision, VcRevision revision) {
        this.target = Objects.requireNonNull(target, "target");
        this.oldRevision = Objects.requireNonNull(oldRevision, "oldRevision");
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    public VcResource target() {
        return target;
    }

    public VcRevision oldRevision() {
        return oldRevision;
    }

    public VcRevision revision() {
        return revision;
    }
}
