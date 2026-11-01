package pro.deta.orion.util;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

public final class VcResourceModified extends OrionEvent {
    private final VcResource<?, ?> source;
    private final VcRevision oldRevision;
    private final VcRevision revision;

    public VcResourceModified(VcResource<?, ?> source, VcRevision oldRevision, VcRevision revision) {
        this.source = Objects.requireNonNull(source, "source");
        this.oldRevision = Objects.requireNonNull(oldRevision, "oldRevision");
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    @Override
    public VcResource<?, ?> source() {
        return source;
    }

    public VcRevision oldRevision() {
        return oldRevision;
    }

    public VcRevision revision() {
        return revision;
    }
}
