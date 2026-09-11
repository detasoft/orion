package pro.deta.orion.auth;

/**
 * Result of renewing a bearer token from an explicit primary-authentication authority.
 */
public sealed interface TokenRefreshResult permits TokenRefreshResult.Success, TokenRefreshResult.Failure {
    record Success(String token, long expiresAtEpochSecond) implements TokenRefreshResult {
    }

    record Failure(String reason, Throwable throwable) implements TokenRefreshResult {
        public Failure(String reason) {
            this(reason, null);
        }
    }

    static TokenRefreshResult success(String token, long expiresAtEpochSecond) {
        return new Success(token, expiresAtEpochSecond);
    }

    static TokenRefreshResult failure(String reason) {
        return new Failure(reason);
    }

    static TokenRefreshResult failure(String reason, Throwable throwable) {
        return new Failure(reason, throwable);
    }
}
