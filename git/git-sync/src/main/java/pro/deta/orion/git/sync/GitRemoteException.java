package pro.deta.orion.git.sync;

import pro.deta.orion.git.client.GitClientFailure;

import java.util.Objects;
import java.util.Optional;

public final class GitRemoteException extends Exception {
    private final boolean retryable;
    private final Optional<GitClientFailure.Kind> clientKind;

    private GitRemoteException(
            String operation,
            boolean retryable,
            GitClientFailure.Kind clientKind,
            Throwable cause) {
        super("Remote Git " + requireOperation(operation) + " failed", cause);
        this.retryable = retryable;
        this.clientKind = Optional.ofNullable(clientKind);
    }

    static GitRemoteException client(
            String operation,
            GitClientFailure failure) {
        GitClientFailure checked = Objects.requireNonNull(failure, "failure");
        return new GitRemoteException(
                operation,
                checked.retryable(),
                checked.kind(),
                checked.cause());
    }

    static GitRemoteException local(
            String operation,
            boolean retryable,
            Throwable cause) {
        return new GitRemoteException(operation, retryable, null, cause);
    }

    public boolean retryable() {
        return retryable;
    }

    public Optional<GitClientFailure.Kind> clientKind() {
        return clientKind;
    }

    private static String requireOperation(String value) {
        Objects.requireNonNull(value, "operation");
        if (value.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return value;
    }
}
