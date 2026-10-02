package pro.deta.orion.schema.orion.v2;

public record RemoteUpdatePolicy(
        boolean allowForceUpdates,
        boolean allowDeletes,
        boolean allowTagRewrites) {
    public static RemoteUpdatePolicy fastForwardOnly() {
        return new RemoteUpdatePolicy(false, false, false);
    }
}
