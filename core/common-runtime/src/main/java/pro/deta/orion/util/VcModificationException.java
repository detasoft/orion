package pro.deta.orion.util;

import java.util.Objects;

/** An operation rejected because the resource does not have the expected revision. */
public final class VcModificationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final VcRevision expectedRevision;
    private final VcRevision actualRevision;

    public VcModificationException(VcRevision expectedRevision, VcRevision actualRevision) {
        super("Resource revision mismatch: expected=" + expectedRevision + ", actual=" + actualRevision);
        this.expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision");
        this.actualRevision = Objects.requireNonNull(actualRevision, "actualRevision");
    }

    public VcRevision expectedRevision() {
        return expectedRevision;
    }

    public VcRevision actualRevision() {
        return actualRevision;
    }
}
