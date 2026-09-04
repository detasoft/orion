package pro.deta.orion.git.nativestorage;

public final class GitRepositoryConcurrentUpdateException extends GitOperationException {
    public GitRepositoryConcurrentUpdateException(String message) {
        super(message);
    }
}
