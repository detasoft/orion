package pro.deta.orion.git.common;

public class GitOperationException extends Exception {
    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
