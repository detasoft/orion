package pro.deta.orion.git.client.machine;

import java.util.Objects;

public final class GitProtocolClientException extends Exception {
    public enum Operation {
        ADVERTISEMENT, NEGOTIATION, PACK, STATUS, SESSION
    }

    private final Operation operation;

    public GitProtocolClientException(Operation operation, String message) {
        this(operation, message, null);
    }

    public GitProtocolClientException(Operation operation, String message, Throwable cause) {
        super(requireMessage(message), cause);
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    public Operation operation() {
        return operation;
    }

    private static String requireMessage(String message) {
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}
