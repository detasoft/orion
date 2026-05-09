package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.GitRepositoryProviderVersionedStorage;
import pro.deta.orion.util.ResourceLocation;

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
        if (resourceLocation.hasNoSchemeOrScheme("file")) {
            return new LocalAccessControlStorage(accessControlConfig);
        }
        if (resourceLocation.hasScheme("local")) {
            return new VersionedAccessControlStorage(
                    new GitRepositoryProviderVersionedStorage(
                            gitRepositoryProvider,
                            resourceLocation.normalizedRelativePath(),
                            accessControlConfig.getBranch()),
                    accessControlConfig.getPaths());
        }
        throw new IllegalArgumentException("Unsupported ACL location: " + location);
    }

    public static boolean isInternalLocalGitStorage(String location) {
        return ResourceLocation.parse(location, "ACL location").hasScheme("local");
    }

    public static String localRepositoryName(String location) {
        return ResourceLocation.parse(location, "ACL location").normalizedRelativePath();
    }
}
