package pro.deta.orion.auth;

/** Invalid caller-supplied ACL or user data, distinct from persistence and internal failures. */
public final class AccessControlValidationException extends IllegalArgumentException {
    public AccessControlValidationException(String message) {
        super(message);
    }
}
