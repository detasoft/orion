package pro.deta.orion.agent.server.registry;

import java.util.Objects;

public final class SessionRegistryException extends Exception {
    private final Reason reason;

    SessionRegistryException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    SessionRegistryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_FOUND,
        CONFLICT,
        IO_FAILURE,
        INDETERMINATE,
        STORED_CORRUPTION,
        CLOSED
    }
}
