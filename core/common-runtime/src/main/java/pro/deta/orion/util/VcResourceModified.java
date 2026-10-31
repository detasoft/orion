package pro.deta.orion.util;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

public class VcResourceModified<T extends VcResource> extends OrionEvent {
    private final T target;
    private final String oldRevision;
    private final String revision;

    public VcResourceModified(T target, String oldRevision, String revision) {
        this.target = target;
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
