package pro.deta.orion.schema.orion.v2;

public record RepositoryPolicy(
        boolean allowForcePushes,
        boolean allowBranchDeletes,
        boolean allowTagRewrites) {
    public static RepositoryPolicy safeDefaults() {
        return new RepositoryPolicy(false, false, false);
    }
}
