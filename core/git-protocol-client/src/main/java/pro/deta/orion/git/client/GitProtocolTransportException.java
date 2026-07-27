package pro.deta.orion.git.client;

import java.util.Objects;

public final class GitProtocolTransportException extends Exception {
    private final Phase phase;
    private final boolean retryable;

    public GitProtocolTransportException(
            Phase phase,
            boolean retryable,
            String message) {
        this(phase, retryable, message, null);
    }

    public GitProtocolTransportException(
            Phase phase,
            boolean retryable,
            String message,
            Throwable cause) {
        super(requireMessage(message), cause);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.retryable = retryable;
    }

    public Phase phase() {
        return phase;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String requireMessage(String message) {
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }

    public enum Phase {
        OPEN,
        WRITE,
        READ,
        CLOSE
    }
}
