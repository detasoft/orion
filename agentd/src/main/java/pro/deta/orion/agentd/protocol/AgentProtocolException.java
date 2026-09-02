package pro.deta.orion.agentd.protocol;

import java.util.Objects;

public final class AgentProtocolException extends Exception {
    public enum Reason {
        MALFORMED_FRAME,
        UNSUPPORTED_VERSION,
        LIMIT_EXCEEDED,
        MISSING_FIELD,
        DUPLICATE_FIELD,
        INVALID_FIELD
    }

    private final Reason reason;

    public AgentProtocolException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public AgentProtocolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
