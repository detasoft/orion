package pro.deta.orion.git.client;

final class GitClientProtocolException extends Exception {
    private final GitClientFailure failure;

    GitClientProtocolException(GitClientFailure failure) {
        super(failure.message(), failure.cause());
        this.failure = failure;
    }

    GitClientFailure failure() {
        return failure;
    }
}
