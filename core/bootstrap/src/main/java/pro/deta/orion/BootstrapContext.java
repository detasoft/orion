package pro.deta.orion;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.s3.S3NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
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
import pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.bootstrap.config.KeyMaterialConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BootstrapContext implements AutoCloseable {
    private static final String FAILURE_MESSAGE = "Bootstrap inputs are unavailable or invalid";

    private final NativeGitRepositoryFactory repositoryFactory;
    private final OrionEventManager events;
    private final OrionKeyMaterial keyMaterial;
    private final SshHostKeyCapability sshHostKeys;

    private BootstrapContext(
            NativeGitRepositoryFactory repositoryFactory,
            OrionEventManager events,
            OrionKeyMaterial keyMaterial,
            SshHostKeyCapability sshHostKeys) {
        this.repositoryFactory = repositoryFactory;
        this.events = events;
        this.keyMaterial = keyMaterial;
        this.sshHostKeys = sshHostKeys;
    }

    public static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment) {
        return open(configuration, environment, new KeyMaterialCreation(false));
    }

    public static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialCreation creation) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(environment, "environment");
        return open(configuration, environment, null, creation);
    }

    static NativeGitRepositoryBackend createRepositoryBackend(
            BootstrapConfiguration configuration, Map<String, String> environment, S3Transport transport,
            OrionEventManager events) {
        String location = configuration.getStorage().getLocation();
        if (ResourceLocation.parse(location, "Storage location").scheme().value().equals("s3")) {
            return S3NativeGitRepositoryFactory.shared(location, configuration.getStorage().getEndpoint(),
                    configuration.getStorage().getAuth(), environment, transport, events);
        }
        ConfigurationContext context = new ConfigurationContext(configuration, environment);
        return NativeGitRepositoryBackend.file(context.getFileGitStoragePath(), events);
    }

    @TestOnly
    static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryBackend backend) {
        return open(configuration, environment, backend, new KeyMaterialCreation(false));
    }

    static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryBackend backend,
            KeyMaterialCreation creation) {
        OrionKeyMaterial keyMaterial = null;
        OrionEventManager events = new OrionEventManager();
        S3Transport s3Transport = new S3Transport();
        NativeGitRepositoryFactory provider = null;
        NativeGitRepositoryBackend selectedBackend = backend;
        try {
            if (selectedBackend == null) {
                selectedBackend = createRepositoryBackend(configuration, environment, s3Transport, events);
            }
            ConfiguredNativeGitRepositoryFactory storageFactory =
                    new ConfiguredNativeGitRepositoryFactory(selectedBackend, s3Transport, events);
            provider = NativeGitRepositoryFactory.bootstrap(storageFactory, environment);
            BootstrapConfigurationSourceConfig configuredConfiguration =
                    repositoryConfiguration(configuration, environment);
            provider.resolveProvisional(
                    NativeGitRepositoryFactory.CONFIGURATION_SOURCE,
                    configuredConfiguration,
                    configuredConfiguration.isCreateDefaultIfMissing());

            KeyMaterialConfig configuredMaterial = configuration.getBootstrap().getKeyMaterial();
            Optional<String> materialRepository = provider.resolveProvisional(
                    NativeGitRepositoryFactory.MATERIAL_SOURCE,
                    configuredMaterial,
                    creation.allowed());
            keyMaterial = openKeyMaterial(configuration, environment, provider.provider(),
                    materialRepository, configuredMaterial, creation);
            SshHostKeyCapability sshHostKeys = SshHostKeyLifecycle.open(
                    keyMaterial.sshHostKeyMaterial(),
                    sshHostKeyReferences(configuration));
            return new BootstrapContext(provider, events, keyMaterial, sshHostKeys);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            try {
                closeResources(s3Transport, provider == null ? null : provider.provider(), keyMaterial);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (selectedBackend == null && failure instanceof RuntimeException invalidStorage) {
                throw invalidStorage;
            }
            throw new IllegalStateException(FAILURE_MESSAGE, failure);
        }
    }

    public OrionEventManager events() {
        return events;
    }

    public S3Transport s3Transport() {
        return storageFactory().transport();
    }

    public ConfiguredNativeGitRepositoryFactory storageFactory() {
        return (ConfiguredNativeGitRepositoryFactory) repositoryFactory.backend();
    }

    public NativeGitRepositoryProvider repositoryProvider() {
        return repositoryFactory.provider();
    }

    public NativeGitRepositoryFactory repositoryFactory() {
        return repositoryFactory;
    }

    public static Optional<OrionDocument> adoptProxies(OrionVersionedConfigurationStorage storage,
            OrionConfigurationEditor editor,
            NativeGitRepositoryFactory repositoryProvider, ConfigurationCipherCapability cipher,
            OrionDocumentRevision approved) {
        Objects.requireNonNull(storage, "configuration storage");
        Objects.requireNonNull(approved, "approved configuration");
        RuntimeException lastSaveFailure = null;
        for (int attempt = 0; attempt <= 3; attempt++) {
            Result<OrionDocumentRevision> loaded = storage.load();
            if (!(loaded instanceof Result.Success<OrionDocumentRevision> success)) {
                return Optional.empty();
            }
            OrionDocumentRevision file = success.value();
            if (!file.revision().equals(approved.revision())) {
                return Optional.empty();
            }
            OrionDocument current = file.document();
            if (file.revision() instanceof OrionRevision.Initial) {
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
            Result<OrionDocumentRevision> prepared = storage.load();
            if (!(prepared instanceof Result.Success<OrionDocumentRevision> preparedSuccess)) {
                return Optional.empty();
            }
            OrionDocumentRevision preparedFile = preparedSuccess.value();
            if (preparedFile.revision() instanceof OrionRevision.Initial
                    || !file.document().equals(preparedFile.document())) {
                return Optional.empty();
            }
            try {
                editor.edit(preparedFile,
                    new ChangeMetadata(UserEmail.EMPTY, "Adopt bootstrap Git proxies"))
                    .update(ignored -> candidate)
                    .apply();
                return Optional.of(candidate);
            } catch (RuntimeException failure) {
                lastSaveFailure = failure;
            }
        }
        throw new IllegalStateException("Proxy adoption did not converge");
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
        closeResources(null, repositoryFactory.provider(), keyMaterial);
    }

    private static void closeResources(S3Transport transport, NativeGitRepositoryProvider provider,
            OrionKeyMaterial material) {
        try {
            if (provider != null) provider.close();
            else if (transport != null) transport.close();
        } finally {
            if (material != null) material.close();
        }
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

    private static OrionKeyMaterial openKeyMaterial(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryProvider provider,
            Optional<String> repositoryName,
            KeyMaterialConfig source,
            KeyMaterialCreation creation) throws IOException, GeneralSecurityException {
        if (repositoryName.isPresent()) {
            return OrionKeyMaterialFactory.open(
                    configuration,
                    environment,
                    new NativeGitKeyMaterialContentStore(
                            provider,
                            repositoryName.orElseThrow(),
                            NativeGitRepositoryFactory.sourceRefName(source),
                            NativeGitRepositoryFactory.repositoryPath(source.getPath())),
                    creation);
        }
        return OrionKeyMaterialFactory.open(configuration, environment, creation);
    }

    private static List<SshHostKeyReference> sshHostKeyReferences(BootstrapConfiguration configuration) {
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
