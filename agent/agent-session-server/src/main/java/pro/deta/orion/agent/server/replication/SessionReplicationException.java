package pro.deta.orion.agent.server.replication;

import java.util.Objects;

public final class SessionReplicationException extends Exception {
    public enum Kind {
        PROTOCOL,
        INTERNAL
    }

    private final Kind kind;

    public SessionReplicationException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public SessionReplicationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
