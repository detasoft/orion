package pro.deta.orion;

import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.config.OrionConfigurationStorage;
import pro.deta.orion.config.OrionConfigurationStorageResolver;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.s3.S3NativeGitRepositoryProvider;
import pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryProvider;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialAdministrationCapability;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.keymaterial.SshHostKeyReference;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.KeyMaterialConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BootstrapContext implements AutoCloseable {
    private static final String FAILURE_MESSAGE = "Bootstrap inputs are unavailable or invalid";

    private final ProxyAwareNativeGitRepositoryProvider repositoryProvider;
    private final BootstrapRepositorySources repositorySources;
    private final OrionKeyMaterial keyMaterial;
    private final ConfiguredNativeGitRepositoryProvider storageProvider;
    private final S3Transport s3Transport;
    private final SshHostKeyCapability sshHostKeys;
    private final Optional<ConfigurationFile> initialConfiguration;

    private BootstrapContext(
            ProxyAwareNativeGitRepositoryProvider repositoryProvider,
            BootstrapRepositorySources repositorySources,
            OrionKeyMaterial keyMaterial,
            SshHostKeyCapability sshHostKeys,
            Optional<ConfigurationFile> initialConfiguration,
            ConfiguredNativeGitRepositoryProvider storageProvider, S3Transport s3Transport) {
        this.repositoryProvider = repositoryProvider;
        this.repositorySources = repositorySources;
        this.keyMaterial = keyMaterial;
        this.sshHostKeys = sshHostKeys;
        this.initialConfiguration = initialConfiguration;
        this.storageProvider = storageProvider;
        this.s3Transport = s3Transport;
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
        return open(configuration, environment, createRepositoryBackend(configuration, environment), createIfMissing);
    }

    static NativeGitRepositoryProvider createRepositoryBackend(
            OrionConfiguration configuration, Map<String, String> environment) {
        String location = configuration.getStorage().getLocation();
        if (ResourceLocation.parse(location, "Storage location").scheme().value().equals("s3")) {
            return new S3NativeGitRepositoryProvider(location, configuration.getStorage().getEndpoint(),
                    configuration.getStorage().getAuth(), environment);
        }
        ConfigurationContext context = new ConfigurationContext(configuration, environment);
        return new FileNativeGitRepositoryProvider(context.getFileGitStoragePath());
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
        S3Transport s3Transport = new S3Transport();
        ConfiguredNativeGitRepositoryProvider storageProvider =
                new ConfiguredNativeGitRepositoryProvider(backend, s3Transport);
        try {
            ProxyAwareNativeGitRepositoryProvider provider =
                    ProxyAwareNativeGitRepositoryProvider.bootstrap(storageProvider, environment);
            BootstrapConfigurationSourceConfig configuredConfiguration =
                    repositoryConfiguration(configuration, environment);
            ResolvedBootstrapSource configurationSource = provider.resolveProvisional(
                    BootstrapRepositorySources.CONFIGURATION,
                    configuredConfiguration,
                    configuredConfiguration.isCreateDefaultIfMissing());

            KeyMaterialConfig configuredMaterial = configuration.getBootstrap().getKeyMaterial();
            ResolvedBootstrapSource materialSource = provider.resolveProvisional(
                    BootstrapRepositorySources.MATERIAL,
                    configuredMaterial,
                    createIfMissing);
            BootstrapRepositorySources sources = new BootstrapRepositorySources(
                    List.of(configurationSource, materialSource));
            Optional<ConfigurationFile> initialConfiguration;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                ResolvedBootstrapSource selectedMaterial = materialSource;
                CompletableFuture<OrionKeyMaterial> materialInput = CompletableFuture.supplyAsync(() -> {
                    try {
                        return openKeyMaterial(configuration, environment, provider, selectedMaterial,
                                createIfMissing);
                    } catch (IOException | GeneralSecurityException error) {
                        throw new CompletionException(error);
                    }
                }, executor);
                try {
                    initialConfiguration = loadInitialConfiguration(sources, provider);
                    keyMaterial = awaitMaterial(materialInput);
                } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                    materialInput.handle((opened, error) -> {
                        if (opened != null) {
                            opened.close();
                        }
                        return null;
                    }).join();
                    throw failure;
                }
            }
            SshHostKeyCapability sshHostKeys = SshHostKeyLifecycle.open(
                    keyMaterial.sshHostKeyMaterial(),
                    sshHostKeyReferences(configuration));
            return new BootstrapContext(provider, sources, keyMaterial, sshHostKeys, initialConfiguration,
                    storageProvider, s3Transport);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            try {
                closeResources(s3Transport, storageProvider, keyMaterial);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw new IllegalStateException(FAILURE_MESSAGE, failure);
        }
    }

    public S3Transport s3Transport() {
        return s3Transport;
    }

    public ConfiguredNativeGitRepositoryProvider storageProvider() {
        return storageProvider;
    }

    public ProxyAwareNativeGitRepositoryProvider repositoryProvider() {
        return repositoryProvider;
    }

    public BootstrapRepositorySources repositorySources() {
        return repositorySources;
    }

    public Optional<ConfigurationFile> initialConfiguration() {
        return initialConfiguration;
    }

    private static OrionKeyMaterial awaitMaterial(CompletableFuture<OrionKeyMaterial> materialInput)
            throws IOException, GeneralSecurityException {
        try {
            return materialInput.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException nested) {
                cause = nested.getCause();
            }
            if (cause instanceof IOException error) {
                throw error;
            }
            if (cause instanceof GeneralSecurityException error) {
                throw error;
            }
            if (cause instanceof RuntimeException error) {
                throw error;
            }
            throw failure;
        }
    }

    private static Optional<ConfigurationFile> loadInitialConfiguration(
            BootstrapRepositorySources sources,
            ProxyAwareNativeGitRepositoryProvider provider) {
        OrionConfigurationStorage storage = new OrionConfigurationStorageResolver(sources, provider).resolve();
        return switch (storage.load()) {
            case Result.Success<ConfigurationFile>(var file) -> Optional.of(file);
            case Result.Failure<ConfigurationFile> failure -> {
                if (failure.code() == Result.FailureCode.NOT_FOUND && storage.createIfMissing()) {
                    yield Optional.empty();
                }
                throw new IllegalStateException("Configuration file is unavailable", failure.throwable());
            }
        };
    }

    public static Optional<OrionDocument> adoptProxies(OrionConfigurationStorage storage, OrionConfigurationEditor editor,
            ProxyAwareNativeGitRepositoryProvider repositoryProvider, ConfigurationCipherCapability cipher,
            OrionDesiredState.Snapshot approved) {
        Objects.requireNonNull(storage, "configuration storage");
        Objects.requireNonNull(approved, "approved configuration");
        RuntimeException lastSaveFailure = null;
        for (int attempt = 0; attempt <= 3; attempt++) {
            Result<ConfigurationFile> loaded = storage.load();
            if (!(loaded instanceof Result.Success<ConfigurationFile> success)) {
                return Optional.empty();
            }
            ConfigurationFile file = success.value();
            if (!file.revision().equals(approved.revision())) {
                return Optional.empty();
            }
            OrionDocument current = proxyConfiguration(file);
            if (file.revision().isEmpty()) {
                throw new IllegalStateException("Proxy adoption requires a configuration revision");
            }
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> current, cipher);
            OrionDocument candidate = repositoryProvider.adoptProvisional(current, secrets);
            if (candidate == current) {
                return Optional.of(current);
            }
            if (lastSaveFailure != null && !(lastSaveFailure instanceof OrionConfigurationConcurrentUpdateException)) {
                throw lastSaveFailure;
            }
            if (attempt == 3) {
                throw new IllegalStateException("Proxy configuration kept changing during adoption", lastSaveFailure);
            }
            Result<ConfigurationFile> prepared = storage.load();
            if (!(prepared instanceof Result.Success<ConfigurationFile> preparedSuccess)) {
                return Optional.empty();
            }
            ConfigurationFile preparedFile = preparedSuccess.value();
            if (preparedFile.revision().isEmpty()
                    || !Arrays.equals(file.content(), preparedFile.content())) {
                return Optional.empty();
            }
            try {
                editor.edit(preparedFile).update(ignored -> candidate)
                        .apply("Adopt bootstrap Git proxies", UserEmail.EMPTY);
                return Optional.of(candidate);
            } catch (OrionConfigurationEditor.ActivationFailedException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                lastSaveFailure = failure;
            }
        }
        throw new IllegalStateException("Proxy adoption did not converge");
    }

    private static OrionDocument proxyConfiguration(ConfigurationFile file) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(file.content())) {
            return OrionXml.read(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot validate proxy configuration", failure);
        }
    }

    public ConfigurationCipherCapability configurationCipher() {
        return keyMaterial.configurationCipher();
    }

    public KeyMaterialAdministrationCapability keyMaterialAdministration() {
        return keyMaterial.administration();
    }

    public ConfigurationMaterialCapability configurationMaterial() {
        return keyMaterial.configurationMaterial();
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
        closeResources(s3Transport, repositoryProvider, keyMaterial);
    }

    private static void closeResources(S3Transport transport, NativeGitRepositoryProvider provider,
            OrionKeyMaterial material) {
        try {
            transport.close();
        } finally {
            try {
                provider.close();
            } finally {
                if (material != null) material.close();
            }
        }
    }

    private static BootstrapConfigurationSourceConfig repositoryConfiguration(
            OrionConfiguration configuration, Map<String, String> environment) throws IOException {
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
