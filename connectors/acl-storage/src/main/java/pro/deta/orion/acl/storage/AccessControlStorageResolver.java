package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AccessControlStorageResolver {
    private final BootstrapRepositorySources repositorySources;
    private final NativeGitRepositoryProvider repositoryProvider;

    public AccessControlStorage resolve() {
        ResolvedBootstrapSource resolved = repositorySources.required(BootstrapRepositorySources.CONFIGURATION);
        if (resolved.repositoryName().isPresent()) {
            return new NativeGitAccessControlStorage(resolved, repositoryProvider);
        }
        BootstrapConfigurationSourceConfig configuration = new BootstrapConfigurationSourceConfig();
        configuration.setLocation(resolved.location());
        configuration.setRef(resolved.refName());
        configuration.setPaths(resolved.paths());
        configuration.setCreateDefaultIfMissing(resolved.createIfMissing());
        String location = resolved.location();
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        return switch (resourceLocation.scheme()) {
            case ResourceScheme.Empty ignored -> new LocalAccessControlStorage(configuration);
            case ResourceScheme.File ignored -> new LocalAccessControlStorage(configuration);
            default -> throw new IllegalArgumentException("Unsupported ACL location: " + location);
        };
    }
}
