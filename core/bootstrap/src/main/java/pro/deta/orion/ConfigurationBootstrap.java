package pro.deta.orion;

import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Prepares the configuration source during bootstrap and opens its storage for runtime. */
public final class ConfigurationBootstrap {
    private ConfigurationBootstrap() {
    }

    static void prepare(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryFactory repositories) throws IOException {
        BootstrapConfigurationSourceConfig source = repositoryConfiguration(configuration, environment);
        repositories.resolveProvisional(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE, source, source.isCreateDefaultIfMissing());
    }

    public static OrionVersionedConfigurationStorage open(
            BootstrapConfiguration configuration,
            NativeGitRepositoryFactory repositoryFactory) {
        return new NativeGitOrionConfigurationStorage(repositoryFactory.fileEvents(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE, configuration.getBootstrap().getAccessControl()));
    }

    private static BootstrapConfigurationSourceConfig repositoryConfiguration(
            BootstrapConfiguration configuration, Map<String, String> environment) throws IOException {
        BootstrapConfigurationSourceConfig configured = configuration.getBootstrap().getAccessControl();
        ResourceLocation location = ResourceLocation.parse(configured.getLocation(), "ACL repository");
        if (!(location.scheme() instanceof ResourceScheme.File)
                && !(location.scheme() instanceof ResourceScheme.Empty)) {
            return configured;
        }
        if (location.uri().getRawAuthority() != null || location.uri().getRawQuery() != null
                || location.uri().getRawFragment() != null) {
            throw new IllegalArgumentException("ACL file location must contain only a local repository path");
        }
        Path directory = directFileRoot(configured.getLocation(),
                ConfigurationContext.baseDirectory(configuration, environment), "ACL repository");
        BootstrapConfigurationSourceConfig source = new BootstrapConfigurationSourceConfig();
        source.setLocation(directory.toUri().toString());
        source.setRef(configured.selectedRef());
        source.setPath(configured.getPath());
        source.setAuth(configured.getAuth());
        source.setCreateDefaultIfMissing(configured.isCreateDefaultIfMissing());
        return source;
    }

    private static Path directFileRoot(
            String configuredLocation,
            Path baseDirectory,
            String label) {
        ResourceLocation location = ResourceLocation.parse(configuredLocation, label);
        Path root = switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> Path.of(location.raw());
            case ResourceScheme.File ignored -> Path.of(location.pathOrSchemeSpecificPart(
                    label + " must include a path"));
            default -> throw new IllegalArgumentException("Bootstrap inputs are unavailable or invalid");
        };
        return root.isAbsolute()
                ? root.toAbsolutePath().normalize()
                : baseDirectory.resolve(root).toAbsolutePath().normalize();
    }
}
