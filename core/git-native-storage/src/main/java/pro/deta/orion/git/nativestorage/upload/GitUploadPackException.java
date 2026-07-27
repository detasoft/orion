package pro.deta.orion.git.nativestorage.upload;

import java.util.Objects;

public final class GitUploadPackException extends RuntimeException {
    private final Kind kind;

    public GitUploadPackException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        MISSING_OBJECT
    }
}
