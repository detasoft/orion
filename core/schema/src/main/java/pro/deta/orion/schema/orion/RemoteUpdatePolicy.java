package pro.deta.orion.schema.orion;

public record RemoteUpdatePolicy(
        boolean allowForceUpdates,
        boolean allowDeletes,
        boolean allowTagRewrites) {
    public static RemoteUpdatePolicy fastForwardOnly() {
        return new RemoteUpdatePolicy(false, false, false);
    }
}
