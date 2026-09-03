package pro.deta.orion.schema.orion;

public record RepositoryPolicy(
        boolean allowForcePushes,
        boolean allowBranchDeletes,
        boolean allowTagRewrites) {
    public static RepositoryPolicy safeDefaults() {
        return new RepositoryPolicy(false, false, false);
    }
}
