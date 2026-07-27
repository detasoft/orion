package pro.deta.orion.git.client.repository;

import java.util.Objects;

public final class GitRepositoryAccessException extends Exception {
    private final Operation operation;
    private final boolean retryable;

    public GitRepositoryAccessException(
            Operation operation,
            boolean retryable,
            String message) {
        this(operation, retryable, message, null);
    }

    public GitRepositoryAccessException(
            Operation operation,
            boolean retryable,
            String message,
            Throwable cause) {
        super(requireMessage(message), cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.retryable = retryable;
    }

    public Operation operation() {
        return operation;
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

    public enum Operation {
        LIST_REFS,
        RESOLVE_REF,
        UPDATE_REF,
        OPEN_PACK,
        READ_PACK,
        WRITE_PACK
    }
}
