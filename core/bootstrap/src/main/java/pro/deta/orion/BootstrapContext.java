package pro.deta.orion;

import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.KeyMaterialConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BootstrapContext implements AutoCloseable {
    private static final String FAILURE_MESSAGE = "Bootstrap inputs are unavailable or invalid";

    private final ProxyAwareNativeGitRepositoryProvider repositoryProvider;
    private final BootstrapRepositorySources repositorySources;
    private final OrionKeyMaterial keyMaterial;

    private BootstrapContext(
            ProxyAwareNativeGitRepositoryProvider repositoryProvider,
            BootstrapRepositorySources repositorySources,
            OrionKeyMaterial keyMaterial) {
        this.repositoryProvider = repositoryProvider;
        this.repositorySources = repositorySources;
        this.keyMaterial = keyMaterial;
    }

    public static BootstrapContext open(
            OrionConfiguration configuration,
            Map<String, String> environment) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(environment, "environment");
        ConfigurationContext configurationContext = new ConfigurationContext(configuration, environment);
        NativeGitRepositoryProvider backend;
        try {
            backend = new FileNativeGitRepositoryProvider(configurationContext.getFileGitStoragePath());
        } catch (IllegalArgumentException ignored) {
            backend = new InMemoryNativeGitRepositoryProvider();
        }
        return open(configuration, environment, backend);
    }

    @TestOnly
    static BootstrapContext open(
            OrionConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryProvider backend) {
        OrionKeyMaterial keyMaterial = null;
        try {
            ProxyAwareNativeGitRepositoryProvider provider =
                    ProxyAwareNativeGitRepositoryProvider.bootstrap(backend, environment);
            BootstrapConfigurationSourceConfig configuredConfiguration =
                    configuration.getBootstrap().getAccessControl();
            ResolvedBootstrapSource configurationSource = provider.resolveProvisional(
                    BootstrapRepositorySources.CONFIGURATION,
                    configuredConfiguration,
                    configuredConfiguration.isCreateDefaultIfMissing());
            configurationSource = validateDirectConfiguration(
                    configuration,
                    environment,
                    configuredConfiguration,
                    configurationSource);

            KeyMaterialConfig configuredMaterial = configuration.getBootstrap().getKeyMaterial();
            ResolvedBootstrapSource materialSource = provider.resolveProvisional(
                    BootstrapRepositorySources.MATERIAL,
                    configuredMaterial,
                    true);
            keyMaterial = openKeyMaterial(
                    configuration,
                    environment,
                    provider,
                    materialSource);
            BootstrapRepositorySources sources = new BootstrapRepositorySources(
                    List.of(configurationSource, materialSource));
            return new BootstrapContext(provider, sources, keyMaterial);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (keyMaterial != null) {
                keyMaterial.close();
            }
            throw new IllegalStateException(FAILURE_MESSAGE, failure);
        }
    }

    public ProxyAwareNativeGitRepositoryProvider repositoryProvider() {
        return repositoryProvider;
    }

    public BootstrapRepositorySources repositorySources() {
        return repositorySources;
    }

    public ServerIdentityCapability serverIdentity() {
        return keyMaterial.serverIdentity();
    }

    public AcmeKeyMaterialCapability acmeKeyMaterial() {
        return keyMaterial.acme();
    }

    public TlsCapability tlsKeyMaterial() {
        return keyMaterial.tls();
    }

    @Override
    public void close() {
        keyMaterial.close();
    }

    private static ResolvedBootstrapSource validateDirectConfiguration(
            OrionConfiguration configuration,
            Map<String, String> environment,
            BootstrapConfigurationSourceConfig configured,
            ResolvedBootstrapSource resolved) {
        if (resolved.repositoryName().isPresent()) {
            return resolved;
        }
        Path baseDirectory = ConfigurationContext.baseDirectory(configuration, environment);
        Path root = directFileRoot(configured.getLocation(), baseDirectory, "Configuration location");
        for (int index = 0; index < resolved.paths().size(); index++) {
            Path path = root.resolve(resolved.paths().get(index)).normalize();
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException(FAILURE_MESSAGE);
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (index == 0 && configured.isCreateDefaultIfMissing()) {
                    return resolvedDirectSource(resolved, root);
                }
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            byte[] bytes = null;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException failure) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            } finally {
                if (bytes != null) {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        return resolvedDirectSource(resolved, root);
    }

    private static ResolvedBootstrapSource resolvedDirectSource(
            ResolvedBootstrapSource resolved,
            Path root) {
        return new ResolvedBootstrapSource(
                resolved.sourceId(),
                root.toUri().toString(),
                resolved.repositoryName(),
                resolved.refName(),
                resolved.paths(),
                resolved.revision(),
                resolved.createIfMissing());
    }

    private static OrionKeyMaterial openKeyMaterial(
            OrionConfiguration configuration,
            Map<String, String> environment,
            ProxyAwareNativeGitRepositoryProvider provider,
            ResolvedBootstrapSource resolved) throws IOException, GeneralSecurityException {
        if (resolved.repositoryName().isPresent()) {
            return OrionKeyMaterialFactory.open(
                    configuration,
                    environment,
                    new NativeGitKeyMaterialContentStore(
                            provider,
                            resolved.repositoryName().orElseThrow(),
                            resolved.refName(),
                            resolved.path()));
        }
        return OrionKeyMaterialFactory.open(configuration, environment);
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
            default -> throw new IllegalArgumentException(FAILURE_MESSAGE);
        };
        return root.isAbsolute()
                ? root.toAbsolutePath().normalize()
                : baseDirectory.resolve(root).toAbsolutePath().normalize();
    }
}
