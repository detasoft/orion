package pro.deta.orion;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.OrionConfigurationModification;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.s3.S3NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.bootstrap.config.KeyMaterialConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pro.deta.orion.event.type.OrionEventPublisher;
import pro.deta.orion.keymaterial.KeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialResourceResolver;
import pro.deta.orion.bootstrap.config.ServerSigningConfig;
import pro.deta.orion.bootstrap.config.SigningKeyReferenceConfig;
import java.util.ArrayList;
import java.util.Locale;

public final class BootstrapContext implements AutoCloseable {
    private static final String FAILURE_MESSAGE = "Bootstrap inputs are unavailable or invalid";

    private final NativeGitRepositoryFactory repositoryFactory;
    private final OrionKeyMaterial keyMaterial;
    private final SigningMaterialSet signingMaterial;

    private BootstrapContext(
            NativeGitRepositoryFactory repositoryFactory,
            OrionKeyMaterial keyMaterial, SigningMaterialSet signingMaterial) {
        this.repositoryFactory = repositoryFactory;
        this.keyMaterial = keyMaterial;
        this.signingMaterial = signingMaterial;
    }

    public static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            OrionEventManager events) {
        return open(configuration, environment, new KeyMaterialCreation(false), events);
    }

    public static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialCreation creation,
            OrionEventManager events) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(environment, "environment");
        return open(configuration, environment, null, creation, events);
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
            NativeGitRepositoryBackend backend,
            OrionEventManager events) {
        return open(configuration, environment, backend, new KeyMaterialCreation(false), events);
    }

    static BootstrapContext open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            NativeGitRepositoryBackend backend,
            KeyMaterialCreation creation,
            OrionEventManager events) {
        Objects.requireNonNull(events, "events");
        OrionKeyMaterial keyMaterial = null;
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
                    materialRepository, configuredMaterial, creation, events);
            return new BootstrapContext(provider, keyMaterial,
                    signingMaterial(configuredMaterial));
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

    public static Optional<OrionDocumentRevision> adoptProxies(
            OrionConfigurationEditor editor,
            NativeGitRepositoryFactory repositoryProvider,
            OrionKeyMaterial keyMaterial,
            OrionDocumentRevision approved) {
        Objects.requireNonNull(editor, "configuration editor");
        Objects.requireNonNull(approved, "approved configuration");
        RuntimeException lastSaveFailure = null;
        for (int attempt = 0; attempt <= 3; attempt++) {
            OrionDocumentRevision file = editor.load();
            if (file.result().isFailure()) {
                return Optional.empty();
            }
            if (!file.revision().equals(approved.revision())) {
                return Optional.empty();
            }
            OrionDocument current = file.result().valueOrFailure("Cannot read configuration document");
            if (file.revision() instanceof OrionRevision.Absent) {
                throw new IllegalStateException("Proxy adoption requires a configuration revision");
            }
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> current, keyMaterial);
            OrionDocument candidate = repositoryProvider.adoptProvisional(current, secrets);
            if (candidate == current) {
                return Optional.of(file);
            }
            if (lastSaveFailure != null && !(lastSaveFailure instanceof OrionConfigurationConcurrentUpdateException)) {
                throw lastSaveFailure;
            }
            if (attempt == 3) {
                throw new IllegalStateException("Proxy configuration kept changing during adoption", lastSaveFailure);
            }
            OrionDocumentRevision preparedFile = editor.load();
            if (preparedFile.result().isFailure()) {
                return Optional.empty();
            }
            if (preparedFile.revision() instanceof OrionRevision.Absent
                    || !file.result()
                            .valueOrFailure("Cannot read configuration document")
                            .equals(preparedFile.result()
                            .valueOrFailure("Cannot read configuration document"))) {
                return Optional.empty();
            }
            try {
                OrionConfigurationModification modification =
                        editor.edit(
                                preparedFile,
                                new ChangeMetadata(UserEmail.EMPTY, "Adopt bootstrap Git proxies"));
                try {
                    modification.update(ignored -> candidate).apply();
                    return Optional.of(
                            new OrionDocumentRevision(Result.of(candidate), modification.revision()));
                } finally {
                    modification.discard();
                }
            } catch (RuntimeException failure) {
                lastSaveFailure = failure;
            }
        }
        throw new IllegalStateException("Proxy adoption did not converge");
    }

    public OrionKeyMaterial keyMaterial() {
        return keyMaterial;
    }

    public SigningMaterialSet signingMaterial() {
        return signingMaterial;
    }

    public synchronized KeyPairProvider sshHostKeys(List<String> references)
            throws IOException, GeneralSecurityException {
        if (references == null) {
            throw new IllegalArgumentException("SSH host key references must not be null");
        }
        for (String reference : references) {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("SSH host key alias must not be empty");
            }
        }
        List<String> selected = List.copyOf(references);
        if (selected.isEmpty()
                && keyMaterial.privateKeyDescriptors(Set.of(KeyMaterialPurpose.SSH_HOST)).isEmpty()) {
            KeyMaterialDescriptor rsa = defaultSshHostKey("ssh-host-rsa", KeyMaterialAlgorithm.RSA);
            KeyMaterialDescriptor ec = defaultSshHostKey("ssh-host-ec", KeyMaterialAlgorithm.EC);
            for (KeyMaterialDescriptor descriptor : List.of(rsa, ec)) {
                if (keyMaterial.contains(descriptor.alias(), Set.of())) {
                    keyMaterial.require(descriptor);
                }
            }
            keyMaterial.withModification(modification -> {
                modification.generateKey(rsa, KeyMaterialConstants.RSA_KEY_SIZE_BITS);
                modification.generateKey(ec, KeyMaterialConstants.EC_KEY_SIZE_BITS);
                modification.apply();
            });
        }
        keyMaterial.sshHostKeyPairs(selected);
        return session -> keyMaterial.sshHostKeyPairs(selected);
    }

    private KeyMaterialDescriptor defaultSshHostKey(String logicalAlias, KeyMaterialAlgorithm algorithm) {
        long version = 1;
        return new KeyMaterialDescriptor(logicalAlias + "-v" + version,
                Set.of(KeyMaterialPurpose.SSH_HOST), algorithm, version);
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
            KeyMaterialCreation creation,
            OrionEventManager events) throws IOException, GeneralSecurityException {
        if (repositoryName.isPresent()) {
            return openKeyMaterial(
                    configuration,
                    environment,
                    new NativeGitKeyMaterialContentStore(
                            provider,
                            repositoryName.orElseThrow(),
                            NativeGitRepositoryFactory.sourceRefName(source),
                            NativeGitRepositoryFactory.repositoryPath(source.getPath())),
                    creation, events);
        }
        return openKeyMaterial(configuration, environment, creation, events);
    }

    static OrionKeyMaterial openKeyMaterial(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialCreation creation,
            OrionEventPublisher eventPublisher) throws IOException, GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("Orion configuration must not be null");
        }
        KeyMaterialConfig material = configuration.getBootstrap().getKeyMaterial();
        requireMaterialConfig(material);
        Path baseDirectory = ConfigurationContext.baseDirectory(configuration, environment);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(environment);
        String location = resolveAgainstBaseDirectory(material.getLocation(), baseDirectory, "location");
        KeyMaterialContentStore store = resolver.resolveStore(location);
        return openKeyMaterial(configuration, environment, store, creation, eventPublisher);
    }

    static OrionKeyMaterial openKeyMaterial(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialContentStore store,
            KeyMaterialCreation creation,
            OrionEventPublisher eventPublisher) throws IOException, GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("Orion configuration must not be null");
        }
        KeyMaterialConfig material = configuration.getBootstrap().getKeyMaterial();
        requireMaterialConfig(material);
        Path baseDirectory = ConfigurationContext.baseDirectory(configuration, environment);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(environment);
        String password = resolveAgainstBaseDirectory(material.getPassword(), baseDirectory, "password");
        SigningMaterialSet signingMaterial = signingMaterial(material);
        try (KeyMaterialOptions options = resolver.pkcs12Options(password)) {
            OrionKeyMaterial keys = OrionKeyMaterial.open(store, options, eventPublisher);
            try {
                if (!keys.hasDurableSnapshot()) {
                    if (!creation.allowed()) {
                        throw new GeneralSecurityException(
                                "Key material store is missing and creation was not requested");
                    }
                    if (!signingMaterial.verification().isEmpty()) {
                        throw new GeneralSecurityException(
                                "A new material store cannot contain retained server identities");
                    }
                    keys.withModification(modification -> {
                        modification.generateKey(
                                signingMaterial.active(), KeyMaterialConstants.RSA_KEY_SIZE_BITS);
                        modification.apply();
                    });
                }
                for (KeyMaterialDescriptor descriptor : signingMaterial.verificationIncludingActive()) {
                    keys.require(descriptor);
                }
                return keys;
            } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                keys.close();
                throw failure;
            }
        }
    }

    public static SigningMaterialSet signingMaterial(KeyMaterialConfig material) {
        requireMaterialConfig(material);
        ServerSigningConfig signing = material.getServerSigning();
        if (signing == null) {
            throw new IllegalArgumentException("Server signing configuration must not be null");
        }
        KeyMaterialAlgorithm algorithm;
        try {
            algorithm = KeyMaterialAlgorithm.valueOf(required(signing.getAlgorithm(), "algorithm")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported server signing algorithm", failure);
        }

        if (algorithm != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server signing material must use RSA");
        }
        KeyMaterialDescriptor active = descriptor(signing.getActive(), algorithm, "active");
        List<KeyMaterialDescriptor> verification = new ArrayList<>();
        if (signing.getVerification() != null) {
            for (SigningKeyReferenceConfig reference : signing.getVerification()) {
                verification.add(descriptor(reference, algorithm, "verification"));
            }
        }
        return new SigningMaterialSet(active, verification);
    }

    private static KeyMaterialDescriptor descriptor(
            SigningKeyReferenceConfig reference,
            KeyMaterialAlgorithm algorithm,
            String role) {
        if (reference == null) {
            throw new IllegalArgumentException("Server signing " + role + " reference must not be null");
        }
        return new KeyMaterialDescriptor(
                required(reference.getAlias(), role + " alias"),
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                algorithm,
                reference.getVersion());
    }

    private static String resolveAgainstBaseDirectory(
            String reference,
            Path baseDirectory,
            String label) {
        ResourceLocation location = ResourceLocation.parse(
                required(reference, "key material " + label), "Key material " + label);
        return switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> baseDirectory.resolve(location.raw()).normalize().toString();
            case ResourceScheme.File ignored -> {
                String value = location.pathOrSchemeSpecificPart(
                        "Key material " + label + " file reference must include a path");
                Path path = Path.of(value);
                yield path.isAbsolute()
                        ? "file:" + path.normalize()
                        : "file:" + baseDirectory.resolve(path).normalize();
            }
            default -> location.raw();
        };
    }

    private static void requireMaterialConfig(KeyMaterialConfig material) {
        if (material == null) {
            throw new IllegalArgumentException("Key material configuration must not be null");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Orion key material " + label + " must not be empty");
        }
        return value;
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
