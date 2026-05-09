package pro.deta.orion.auth;

/**
 * Result of issuing a bearer token after successful primary authentication.
 */
public sealed interface TokenIssueResult permits TokenIssueResult.Success, TokenIssueResult.Failure {
    record Success(String token, long expiresAtEpochSecond) implements TokenIssueResult {
    }

    record Failure(String reason, Throwable throwable) implements TokenIssueResult {
        public Failure(String reason) {
            this(reason, null);
        }
    }

    static TokenIssueResult success(String token, long expiresAtEpochSecond) {
        return new Success(token, expiresAtEpochSecond);
    }

    static TokenIssueResult failure(String reason) {
        return new Failure(reason);
    }

    static TokenIssueResult failure(String reason, Throwable throwable) {
        return new Failure(reason, throwable);
    }
}
