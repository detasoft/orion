package pro.deta.orion.agent.server.journal;

import java.util.Objects;

public final class JournalStorageException extends Exception {
    public enum Reason {
        INVALID_APPEND,
        CONFLICTING_DUPLICATE,
        STORED_CORRUPTION,
        IO_FAILURE,
        CLOSED
    }

    private final Reason reason;

    public JournalStorageException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public JournalStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
