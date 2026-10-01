package pro.deta.orion.provisioning;

public record ProvisioningResult(
        RemotePlatform platform,
        String version,
        String releaseDirectory
) {
    public ProvisioningResult {
        if (platform == null || version == null || releaseDirectory == null) {
            throw new IllegalArgumentException("Provisioning result fields must not be null");
        }
    }
}
