package pro.deta.orion;

import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.keymaterial.SshHostKeyReference;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.KeyMaterialConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BootstrapContext implements AutoCloseable {
    private static final String FAILURE_MESSAGE = "Bootstrap inputs are unavailable or invalid";

    private final ProxyAwareNativeGitRepositoryProvider repositoryProvider;
    private final BootstrapRepositorySources repositorySources;
    private final OrionKeyMaterial keyMaterial;
    private final SshHostKeyCapability sshHostKeys;

    private BootstrapContext(
            ProxyAwareNativeGitRepositoryProvider repositoryProvider,
            BootstrapRepositorySources repositorySources,
            OrionKeyMaterial keyMaterial,
            SshHostKeyCapability sshHostKeys) {
        this.repositoryProvider = repositoryProvider;
        this.repositorySources = repositorySources;
        this.keyMaterial = keyMaterial;
        this.sshHostKeys = sshHostKeys;
    }

    public static BootstrapContext open(
            OrionConfiguration configuration,
            Map<String, String> environment) {
        return open(configuration, environment, false);
    }

    public static BootstrapContext open(
            OrionConfiguration configuration,
            Map<String, String> environment,
            boolean createIfMissing) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(environment, "environment");
        ConfigurationContext configurationContext = new ConfigurationContext(configuration, environment);
        NativeGitRepositoryProvider backend;
        try {
            backend = new FileNativeGitRepositoryProvider(configurationContext.getFileGitStoragePath());
        } catch (IllegalArgumentException ignored) {
            backend = new InMemoryNativeGitRepositoryProvider();
        }
        return open(configuration, environment, backend, createIfMissing);
    }

    @TestOnly
    static BootstrapContext open(
            OrionConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryProvider backend) {
        return open(configuration, environment, backend, false);
    }

    @TestOnly
    static BootstrapContext open(
            OrionConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryProvider backend,
            boolean createIfMissing) {
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
                    createIfMissing);
            keyMaterial = openKeyMaterial(
                    configuration,
                    environment,
                    provider,
                    materialSource,
                    createIfMissing);
            SshHostKeyCapability sshHostKeys = SshHostKeyLifecycle.open(
                    keyMaterial.sshHostKeyMaterial(),
                    sshHostKeyReferences(configuration));
            BootstrapRepositorySources sources = new BootstrapRepositorySources(
                    List.of(configurationSource, materialSource));
            return new BootstrapContext(provider, sources, keyMaterial, sshHostKeys);
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

    public static OrionDocument adoptProxies(AccessControlStorage storage,
            ProxyAwareNativeGitRepositoryProvider repositoryProvider, ConfigurationCipherCapability cipher) {
        Objects.requireNonNull(storage, "configuration storage");
        RuntimeException lastSaveFailure = null;
        for (int attempt = 0; attempt <= 3; attempt++) {
            AccessControlSnapshot snapshot = storage.load().valueOrFailure("Cannot load proxy configuration");
            OrionDocument current = proxyConfiguration(snapshot, storage.primaryPath());
            if (snapshot.version().isEmpty()) {
                throw new IllegalStateException("Proxy adoption requires a configuration revision");
            }
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> current, cipher);
            OrionDocument candidate = repositoryProvider.adoptProvisional(current, secrets);
            if (candidate == current) {
                return current;
            }
            if (lastSaveFailure != null && !(lastSaveFailure instanceof AccessControlConcurrentUpdateException)) {
                throw lastSaveFailure;
            }
            if (attempt == 3) {
                throw new IllegalStateException("Proxy configuration kept changing during adoption", lastSaveFailure);
            }
            Map<String, byte[]> updatedFiles = new LinkedHashMap<>(snapshot.files());
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                OrionXml.write(candidate, output);
                updatedFiles.put(storage.primaryPath(), output.toByteArray());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot serialize proxy configuration");
            }
            try {
                storage.save(new AccessControlSnapshot(updatedFiles, snapshot.version()),
                        new AccessControlSaveRequest("Adopt bootstrap Git proxies", UserEmail.EMPTY));
                lastSaveFailure = null;
            } catch (RuntimeException failure) {
                lastSaveFailure = failure;
            }
        }
        throw new IllegalStateException("Proxy adoption did not converge");
    }

    private static OrionDocument proxyConfiguration(AccessControlSnapshot snapshot, String primaryPath) {
        OrionDocument primary = null;
        for (var entry : snapshot.files().entrySet()) {
            try (var input = new ByteArrayInputStream(entry.getValue())) {
                OrionDocument parsed = OrionXml.read(input);
                if (entry.getKey().equals(primaryPath)) {
                    primary = parsed;
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot validate proxy configuration");
            }
        }
        if (primary == null) {
            throw new IllegalStateException("Primary proxy configuration is unavailable");
        }
        return primary;
    }

    public ConfigurationCipherCapability configurationCipher() {
        return keyMaterial.configurationCipher();
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

    public SshHostKeyCapability sshHostKeys() {
        return sshHostKeys;
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
        List<Path> paths = new ArrayList<>();
        for (String configuredPath : resolved.paths()) {
            paths.add(LocalAccessControlStorage.resolvePath(root, configuredPath));
        }
        for (int index = 0; index < paths.size(); index++) {
            Path path = paths.get(index);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (index == 0 && configured.isCreateDefaultIfMissing()) {
                    return resolvedDirectSource(resolved, root);
                }
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            byte[] bytes = null;
            try (java.io.InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readAllBytes();
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
            ResolvedBootstrapSource resolved,
            boolean createIfMissing) throws IOException, GeneralSecurityException {
        if (resolved.repositoryName().isPresent()) {
            return OrionKeyMaterialFactory.open(
                    configuration,
                    environment,
                    new NativeGitKeyMaterialContentStore(
                            provider,
                            resolved.repositoryName().orElseThrow(),
                            resolved.refName(),
                            resolved.path()),
                    createIfMissing);
        }
        return OrionKeyMaterialFactory.open(configuration, environment, createIfMissing);
    }

    private static List<SshHostKeyReference> sshHostKeyReferences(OrionConfiguration configuration) {
        if (configuration.getTransport().getSsh().getHostKeys() == null) {
            throw new IllegalArgumentException("SSH host key references must not be null");
        }
        List<SshHostKeyReference> references = new ArrayList<>();
        for (var configured : configuration.getTransport().getSsh().getHostKeys()) {
            if (configured == null) {
                throw new IllegalArgumentException("SSH host key reference must not be null");
            }
            references.add(new SshHostKeyReference(configured.getAlias()));
        }
        return List.copyOf(references);
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
