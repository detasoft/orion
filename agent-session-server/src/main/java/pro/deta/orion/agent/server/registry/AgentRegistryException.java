package pro.deta.orion.agent.server.registry;

import java.util.Objects;

public final class AgentRegistryException extends Exception {
    public enum Reason {
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT,
        STORED_CORRUPTION,
        IO_FAILURE,
        INDETERMINATE,
        CLOSED
    }

    private final Reason reason;

    AgentRegistryException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    AgentRegistryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
