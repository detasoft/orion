package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AccessControlStorageResolver {
    private final OrionConfiguration configuration;
    private final NativeGitRepositoryProvider repositoryProvider;

    public AccessControlStorage resolve() {
        return resolve(configuration.getBootstrap().getAccessControl());
    }

    AccessControlStorage resolve(OrionConfiguration.BootstrapAccessControlConfig accessControlConfig) {
        String location = accessControlConfig.getLocation();
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        return switch (resourceLocation.scheme()) {
            case ResourceScheme.Empty ignored -> new LocalAccessControlStorage(accessControlConfig);
            case ResourceScheme.File ignored -> new LocalAccessControlStorage(accessControlConfig);
            case ResourceScheme.Local ignored ->
                    new NativeGitAccessControlStorage(accessControlConfig, repositoryProvider);
            case ResourceScheme.Other ignored when S3AccessControlStorage.supportsLocation(location) ->
                    new S3AccessControlStorage(accessControlConfig);
            case ResourceScheme.Other ignored -> throw new IllegalArgumentException("Unsupported ACL location: " + location);
        };
    }
}
