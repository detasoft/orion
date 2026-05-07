package pro.deta.orion.auth;

/**
 * Result of credential verification. It belongs to the authorization API so callers do not depend on the
 * broader application-level Result type from common.
 */
public sealed interface AuthenticationResult permits AuthenticationResult.Success, AuthenticationResult.Failure {
    record Success(UserIdentity userIdentity) implements AuthenticationResult {
    }

    record Failure(String reason, Throwable throwable) implements AuthenticationResult {
        public Failure(String reason) {
            this(reason, null);
        }
    }

    static AuthenticationResult success(UserIdentity userIdentity) {
        return new Success(userIdentity);
    }

    static AuthenticationResult failure(String reason) {
        return new Failure(reason);
    }

    static AuthenticationResult failure(String reason, Throwable throwable) {
        return new Failure(reason, throwable);
    }
}
