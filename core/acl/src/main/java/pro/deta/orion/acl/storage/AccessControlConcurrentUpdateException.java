package pro.deta.orion.acl.storage;

public final class AccessControlConcurrentUpdateException extends RuntimeException {
    public AccessControlConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
