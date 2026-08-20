package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.GitRepositoryProviderVersionedStorage;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AccessControlStorageResolver {
    private final OrionConfiguration configuration;
    private final GitRepositoryProvider gitRepositoryProvider;

    public AccessControlStorage resolve() {
        return resolve(configuration.getBootstrap().getAccessControl());
    }

    AccessControlStorage resolve(OrionConfiguration.BootstrapAccessControlConfig accessControlConfig) {
        String location = accessControlConfig.getLocation();
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        return switch (resourceLocation.scheme()) {
            case ResourceScheme.Empty ignored -> new LocalAccessControlStorage(accessControlConfig);
            case ResourceScheme.File ignored -> new LocalAccessControlStorage(accessControlConfig);
            case ResourceScheme.Local ignored -> new VersionedAccessControlStorage(
                    new GitRepositoryProviderVersionedStorage(
                            gitRepositoryProvider,
                            resourceLocation.normalizedRelativePath(),
                            accessControlConfig.getBranch()),
                    accessControlConfig.getPaths());
            case ResourceScheme.Other ignored when RemoteGitAccessControlStorage.supportsLocation(location) ->
                    new RemoteGitAccessControlStorage(configuration, accessControlConfig);
            case ResourceScheme.Other ignored when S3AccessControlStorage.supportsLocation(location) ->
                    new S3AccessControlStorage(accessControlConfig);
            case ResourceScheme.Other ignored -> throw new IllegalArgumentException("Unsupported ACL location: " + location);
        };
    }

    public static boolean isInternalLocalGitStorage(String location) {
        return switch (ResourceLocation.parse(location, "ACL location").scheme()) {
            case ResourceScheme.Local ignored -> true;
            default -> false;
        };
    }

    public static String localRepositoryName(String location) {
        return ResourceLocation.parse(location, "ACL location").normalizedRelativePath();
    }
}
