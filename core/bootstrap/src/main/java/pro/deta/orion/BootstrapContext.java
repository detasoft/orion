package pro.deta.orion;

import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
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
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;

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
    private final S3NativeGitRepositoryProvider standaloneS3;
    private final SshHostKeyCapability sshHostKeys;
    private final Optional<AccessControlSnapshot> initialConfiguration;

    private BootstrapContext(
            ProxyAwareNativeGitRepositoryProvider repositoryProvider,
            BootstrapRepositorySources repositorySources,
            OrionKeyMaterial keyMaterial,
            SshHostKeyCapability sshHostKeys,
            Optional<AccessControlSnapshot> initialConfiguration,
            ConfiguredNativeGitRepositoryProvider storageProvider, S3Transport s3Transport,
            S3NativeGitRepositoryProvider standaloneS3) {
        this.repositoryProvider = repositoryProvider;
        this.repositorySources = repositorySources;
        this.keyMaterial = keyMaterial;
        this.sshHostKeys = sshHostKeys;
        this.initialConfiguration = initialConfiguration;
        this.storageProvider = storageProvider;
        this.s3Transport = s3Transport;
        this.standaloneS3 = standaloneS3;
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
        S3NativeGitRepositoryProvider standaloneS3 = backend instanceof S3NativeGitRepositoryProvider s3 ? s3 : null;
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
            Optional<AccessControlSnapshot> initialConfiguration;
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
                    storageProvider, s3Transport, standaloneS3);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            closeResources(s3Transport, standaloneS3, keyMaterial);
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

    public Optional<AccessControlSnapshot> initialConfiguration() {
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

    private static Optional<AccessControlSnapshot> loadInitialConfiguration(
            BootstrapRepositorySources sources,
            ProxyAwareNativeGitRepositoryProvider provider) {
        AccessControlStorage storage = new AccessControlStorageResolver(sources, provider).resolve();
        return switch (storage.load()) {
            case Result.Success<AccessControlSnapshot>(var snapshot) -> Optional.of(snapshot);
            case Result.Failure<AccessControlSnapshot> failure -> {
                if (failure.code() == Result.FailureCode.NOT_FOUND && storage.createIfMissing()) {
                    yield Optional.empty();
                }
                throw new IllegalStateException("Configuration snapshot is unavailable", failure.throwable());
            }
        };
    }

    public static Optional<OrionDocument> adoptProxies(AccessControlStorage storage,
            ProxyAwareNativeGitRepositoryProvider repositoryProvider, ConfigurationCipherCapability cipher,
            OrionDesiredState.Snapshot approved) {
        Objects.requireNonNull(storage, "configuration storage");
        Objects.requireNonNull(approved, "approved configuration");
        RuntimeException lastSaveFailure = null;
        for (int attempt = 0; attempt <= 3; attempt++) {
            Result<AccessControlSnapshot> loaded = storage.load();
            if (!(loaded instanceof Result.Success<AccessControlSnapshot> success)) {
                return Optional.empty();
            }
            AccessControlSnapshot snapshot = success.value();
            if (!snapshot.version().equals(approved.revision())) {
                return Optional.empty();
            }
            OrionDocument current = proxyConfiguration(snapshot, storage.primaryPath());
            if (snapshot.version().isEmpty()) {
                throw new IllegalStateException("Proxy adoption requires a configuration revision");
            }
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> current, cipher);
            OrionDocument candidate = repositoryProvider.adoptProvisional(current, secrets);
            if (candidate == current) {
                return Optional.of(current);
            }
            if (lastSaveFailure != null && !(lastSaveFailure instanceof AccessControlConcurrentUpdateException)) {
                throw lastSaveFailure;
            }
            if (attempt == 3) {
                throw new IllegalStateException("Proxy configuration kept changing during adoption", lastSaveFailure);
            }
            Result<AccessControlSnapshot> prepared = storage.load();
            if (!(prepared instanceof Result.Success<AccessControlSnapshot> preparedSuccess)) {
                return Optional.empty();
            }
            AccessControlSnapshot preparedSnapshot = preparedSuccess.value();
            Map<String, byte[]> currentFiles = snapshot.files();
            Map<String, byte[]> preparedFiles = preparedSnapshot.files();
            if (preparedSnapshot.version().isEmpty() || !currentFiles.keySet().equals(preparedFiles.keySet())) {
                return Optional.empty();
            }
            for (Map.Entry<String, byte[]> file : currentFiles.entrySet()) {
                if (!Arrays.equals(file.getValue(), preparedFiles.get(file.getKey()))) {
                    return Optional.empty();
                }
            }
            Map<String, byte[]> updatedFiles = new LinkedHashMap<>(preparedFiles);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                OrionXml.write(candidate, output);
                updatedFiles.put(storage.primaryPath(), output.toByteArray());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot serialize proxy configuration");
            }
            try {
                storage.save(new AccessControlSnapshot(updatedFiles, preparedSnapshot.version()),
                        new AccessControlSaveRequest("Adopt bootstrap Git proxies", UserEmail.EMPTY));
                return Optional.of(candidate);
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
        closeResources(s3Transport, standaloneS3, keyMaterial);
    }

    private static void closeResources(S3Transport transport, S3NativeGitRepositoryProvider standalone,
            OrionKeyMaterial material) {
        try {
            transport.close();
        } finally {
            try {
                if (standalone != null) standalone.close();
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
        source.setPaths(configured.selectedPaths());
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
