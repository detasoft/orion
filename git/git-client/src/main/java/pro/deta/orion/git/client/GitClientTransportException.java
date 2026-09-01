package pro.deta.orion.git.client;

import java.io.IOException;
import java.util.Objects;

public final class GitClientTransportException extends IOException {
    private final GitClientFailure.Kind kind;
    private final boolean retryable;

    public GitClientTransportException(
            GitClientFailure.Kind kind,
            boolean retryable,
            String message,
            Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryable = retryable;
    }

    public GitClientTransportException(
            GitClientFailure.Kind kind,
            boolean retryable,
            String message) {
        this(kind, retryable, message, null);
    }

    public GitClientFailure.Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }
}
