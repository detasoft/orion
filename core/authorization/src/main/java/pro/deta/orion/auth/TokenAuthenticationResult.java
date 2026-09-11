package pro.deta.orion.auth;

public sealed interface TokenAuthenticationResult
        permits TokenAuthenticationResult.Success, TokenAuthenticationResult.Failure {
    record Success(
            UserIdentity userIdentity,
            AccessTokenIdentity tokenIdentity) implements TokenAuthenticationResult {
    }

    record Failure(String reason, Throwable throwable) implements TokenAuthenticationResult {
        public Failure(String reason) {
            this(reason, null);
        }
    }

    static TokenAuthenticationResult success(
            UserIdentity userIdentity,
            AccessTokenIdentity tokenIdentity) {
        return new Success(userIdentity, tokenIdentity);
    }

    static TokenAuthenticationResult failure(String reason) {
        return new Failure(reason);
    }

    static TokenAuthenticationResult failure(String reason, Throwable throwable) {
        return new Failure(reason, throwable);
    }
}
