package pro.deta.orion.auth;

public record AccessControlRepositoryGrantUpdate(
        String repository,
        boolean read,
        boolean write,
        boolean create,
        boolean force,
        String branch) {
    public AccessControlRepositoryGrantUpdate {
        if (branch == null || branch.isBlank()) {
            branch = "*";
        }
    }
}
