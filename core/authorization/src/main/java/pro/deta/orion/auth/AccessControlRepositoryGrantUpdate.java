package pro.deta.orion.auth;

public record AccessControlRepositoryGrantUpdate(
        String repository,
        boolean read,
        boolean readWrite,
        boolean create,
        boolean force,
        String branch) {
    public AccessControlRepositoryGrantUpdate {
        if (branch == null || branch.isBlank()) {
            branch = "*";
        }
    }
}
