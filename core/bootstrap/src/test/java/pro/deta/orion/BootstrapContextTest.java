package pro.deta.orion;

import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.config.OrionConfigurationEditor;

import org.eclipse.jgit.api.Git;
import com.sun.net.httpserver.HttpServer;
import pro.deta.orion.bootstrap.config.location.BootstrapConfigurationReader;

import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.internal.ChangeMetadata;
import java.net.InetSocketAddress;
import pro.deta.orion.schema.orion.v2.ConfigurationSecret;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.s3.S3NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.KeyMaterialSnapshot;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.SigningKeyReferenceConfig;
import pro.deta.orion.bootstrap.config.SshHostKeyReferenceConfig;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;
import pro.deta.orion.schema.orion.v2.GitCredentialKind;
import pro.deta.orion.schema.orion.v2.RemoteAlias;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BootstrapContextTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";
    private static final Map<String, String> ENVIRONMENT = Map.of(PASSWORD_ENV, "correct-password");

    @TempDir
    private Path tempDir;

    private static pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend borrow(
            NativeGitRepositoryProvider owner) {
        return backend(owner, false);
    }

    private static pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend backend(
            NativeGitRepositoryProvider owner, boolean closeOwner) {
        return new pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend() {
            @Override
            public void close() {
                if (closeOwner) owner.close();
            }

            @Override
            public List<String> repositoryNames() {
                return owner.repositoryNames();
            }

            @Override
            public boolean exists(pro.deta.orion.schema.orion.v2.RepositoryName name) {
                return owner.exists(name.value());
            }

            @Override
            public Result<NativeGitRepository> open(pro.deta.orion.schema.orion.v2.RepositoryName name) {
                return borrowed(owner.find(name.value()));
            }

            @Override
            public Result<NativeGitRepository> create(pro.deta.orion.schema.orion.v2.RepositoryName name) {
                return borrowed(owner.create(name.value()));
            }
        };
    }

    private static Result<NativeGitRepository> borrowed(Result<NativeGitRepository> result) {
        if (result instanceof Result.Success<NativeGitRepository> success) {
            NativeGitRepository repository = success.value();
            return new Result.Success<>(new NativeGitRepository(repository.name(), repository.storage(),
                    repository.index(), EVENTS.rawManager()) {
                @Override
                public void close() {
                }
            });
        }
        return result;
    }

    private static BootstrapContext openContext(BootstrapConfiguration configuration, Map<String, String> environment) {
        return BootstrapContext.open(configuration, environment, EVENTS.rawManager());
    }

    private static BootstrapContext openContext(BootstrapConfiguration configuration, Map<String, String> environment,
            boolean createIfMissing) {
        return BootstrapContext.open(
                configuration,
                environment,
                new KeyMaterialCreation(createIfMissing),
                EVENTS.rawManager());
    }

    private static BootstrapContext openContext(BootstrapConfiguration configuration, Map<String, String> environment,
            NativeGitRepositoryProvider owner) {
        return BootstrapContext.open(configuration, environment, backend(owner, true), EVENTS.rawManager());
    }

    private static BootstrapContext openContext(BootstrapConfiguration configuration, Map<String, String> environment,
            NativeGitRepositoryProvider owner, boolean createIfMissing) {
        return BootstrapContext.open(configuration, environment, backend(owner, true),
                new KeyMaterialCreation(createIfMissing), EVENTS.rawManager());
    }

    private static BootstrapContext openContext(BootstrapConfiguration configuration, Map<String, String> environment,
            pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend backing) {
        return BootstrapContext.open(configuration, environment, backing, EVENTS.rawManager());
    }

    @Test
    void componentExposesTheSameS3TransportUsedByConfiguredRepositories() throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(configuration, Map.of(
                "orion.xml", xml(), "material.p12", materialBytes(configuration)));
        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            OrionComponent component = runtimeComponent(configuration, context);
            assertThat(component.s3Transport()).isSameAs(context.s3Transport());
            context.storageFactory().repositoryNames();
            component.s3Transport().close();
            assertThatThrownBy(() -> context.storageFactory().repositoryNames())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
    }

    @Test
    void opensConfigurationAndMaterialFromOneLocalRepository() throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));
        String initialRevision = backend.find("orion").valueOrFailure("configuration repository")
                .refs().get("refs/heads/main");

        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            String configurationRepository = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                    .orElseThrow();
            String materialRepository = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE)
                    .orElseThrow();

            assertThat(configurationRepository).isEqualTo("orion");
            assertThat(materialRepository).isEqualTo(configurationRepository);
            assertThat(context.repositoryProvider().repositoryNames()).containsExactly("orion");
            assertThat(context.repositoryProvider().find(configurationRepository)
                    .valueOrFailure("configuration repository").refs().get("refs/heads/main"))
                    .isEqualTo(initialRevision);
            assertThat(context.serverIdentity().activeKeyId()).isNotBlank();
            assertThat(context.acmeKeyMaterial()).isNotNull();
            assertThat(context.tlsKeyMaterial()).isNotNull();
            assertThat(context.sshHostKeys(List.of()).descriptors())
                    .extracting(descriptor -> descriptor.alias().value())
                    .containsExactly("ssh-host-ec-v1", "ssh-host-rsa-v1");
        }
        assertThatThrownBy(() -> backend.find("orion")).hasMessageContaining("closed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"configuration", "material"})
    void pinnedConfigurationReadsRemainIndependentOfLaterBootstrapSourceUpdates(String delayedInput)
            throws Exception {
        BootstrapConfiguration configuration = configuration();
        OrionDocument first = OrionDocument.withAccessControl(new AccessControl());
        NativeGitRepositoryProvider backend = repositoryWith(configuration, Map.of(
                "orion.xml", xml(), "material.p12", materialBytes(configuration)));
        NativeGitRepository repository = backend.find("orion").valueOrFailure("seeded repository");
        String firstCommit = repository.refs().get("refs/heads/main");
        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            OrionDocument updated = first.replaceAccessControl(new AccessControl(
                    List.of(new User("later-user", null, null, null, List.of(), List.of(), List.of())),
                    List.of(), List.of()));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OrionXml.write(updated, output);
            repository
                    .files()
                    .withAccess(
                            "refs/heads/main",
                            new GitCommitAuthor("", "", "advance bootstrap source"),
                            access -> {
                                access.write(
                                        "configuration".equals(delayedInput)
                                                ? "orion.xml"
                                                : "material.p12",
                                        "configuration".equals(delayedInput)
                                                ? output.toByteArray()
                                                : bytes("replaced material"));
                                access.apply();
                                return null;
                            });
            try (NativeGitOrionConfigurationStorage storage = new NativeGitOrionConfigurationStorage(
                    context.repositoryFactory(), configuration.getBootstrap().getAccessControl())) {
                assertThat(storage.load(new OrionRevision.Stored(firstCommit)).valueOrFailure("pinned revision")
                        .document()).isEqualTo(first);
                assertThat(storage.load().valueOrFailure("current revision").document())
                        .isEqualTo("configuration".equals(delayedInput) ? updated : first);
            }
            byte[] payload = bytes("already loaded material");
            assertThat(context.serverIdentity().verify(context.serverIdentity().activeKeyId(), payload,
                    context.serverIdentity().sign(payload))).isTrue();
        }
    }

    @Test
    void rejectsAnUnresolvedExplicitSshHostKeyWhenSelectingHostKeys() throws Exception {
        BootstrapConfiguration configuration = configuration();
        SshHostKeyReferenceConfig reference = new SshHostKeyReferenceConfig();
        reference.setAlias("missing-ssh-host-key");
        configuration.getTransport().getSsh().setHostKeys(List.of(reference));
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            assertThatThrownBy(() -> context.sshHostKeys(List.of(
                    new pro.deta.orion.keymaterial.SshHostKeyReference(reference.getAlias()))))
                    .isInstanceOf(java.security.GeneralSecurityException.class)
                    .hasMessageContaining("missing-ssh-host-key");
        }
        assertThatThrownBy(() -> backend.find("orion")).hasMessageContaining("closed");
    }

    @Test
    void keepsExistingRuntimeWhenConfigurationReferencesUnstagedSigningMaterial() throws Exception {
        BootstrapConfiguration initial = configuration();
        try (NativeGitRepositoryProvider backend = repositoryWith(
                initial,
                Map.of("orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(initial)))) {
            BootstrapConfiguration next = configuration();
            next.getBootstrap().getKeyMaterial().getServerSigning()
                    .setActive(new SigningKeyReferenceConfig("server-signing-v2", 2));
            next.getBootstrap().getKeyMaterial().getServerSigning()
                    .setVerification(List.of(new SigningKeyReferenceConfig("server-signing-v1", 1)));
            byte[] payload = bytes("bootstrap-rotation");
            byte[] oldSignature;

            try (BootstrapContext current = openContext(initial, ENVIRONMENT, borrow(backend))) {
                oldSignature = current.serverIdentity().sign(payload);
                assertThatThrownBy(() -> openContext(next, ENVIRONMENT, borrow(backend)))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Bootstrap inputs are unavailable or invalid")
                        .rootCause()
                        .hasMessageContaining("server-signing-v2");
                assertThat(current.serverIdentity().verify("server-signing-v1", payload, oldSignature)).isTrue();

                KeyMaterialDescriptor staged = new KeyMaterialDescriptor(
                        new KeyMaterialAlias("server-signing-v2"),
                        KeyMaterialPurpose.SERVER_SIGNING,
                        KeyMaterialAlgorithm.RSA,
                        new KeyMaterialVersion(2),
                        KeyMaterialScope.cluster("orion"));
                try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("correct-password".toCharArray());
                     KeyMaterialService material = KeyMaterialService.open(
                             new NativeGitKeyMaterialContentStore(
                                     backend, "orion", "refs/heads/main", "material.p12"), options)) {
                    material.generateKeyIfMissing(staged, 2048);
                    material.save();
                }

                try (BootstrapContext activated = openContext(next, ENVIRONMENT, borrow(backend))) {
                    assertThat(activated.serverIdentity().activeKeyId()).isEqualTo("server-signing-v2");
                    assertThat(activated.serverIdentity().verify("server-signing-v1", payload, oldSignature)).isTrue();
                }
            }

            try (BootstrapContext restored = openContext(initial, ENVIRONMENT, borrow(backend))) {
                assertThat(restored.serverIdentity().activeKeyId()).isEqualTo("server-signing-v1");
                assertThat(restored.serverIdentity().verify("server-signing-v1", payload, oldSignature)).isTrue();
            }
        }
    }

    @Test
    void doesNotRecreateLostMaterialForConfigurationWithRetainedIdentity() throws Exception {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().getServerSigning()
                .setActive(new SigningKeyReferenceConfig("server-signing-v2", 2));
        configuration.getBootstrap().getKeyMaterial().getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("server-signing-v1", 1)));
        try (NativeGitRepositoryProvider backend = repositoryWith(
                configuration, Map.of("orion.xml", bytes("configuration")))) {
            assertThatThrownBy(() -> openContext(configuration, ENVIRONMENT, borrow(backend)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap inputs are unavailable or invalid")
                    .rootCause()
                    .hasMessageContaining("Bootstrap source path is unavailable: material");
            assertThat(new NativeGitKeyMaterialContentStore(
                    backend, "orion", "refs/heads/main", "material.p12").read()).isEmpty();
        }
    }

    @Test
    void restoresPinnedConfigurationAndMaterialBytesWithoutChangingSigningIdentity() throws Exception {
        BootstrapConfiguration configuration = configuration();
        try (NativeGitRepositoryProvider source = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)))) {
            byte[] payload = bytes("restored-identity");
            byte[] signature;
            try (BootstrapContext original = openContext(configuration, ENVIRONMENT, borrow(source))) {
                signature = original.serverIdentity().sign(payload);
            }
            Map<String, byte[]> backup = Map.of("orion.xml", source.find("orion")
                    .valueOrFailure("open repository")
                    .files().readBytes("refs/heads/main", "orion.xml"), "material.p12", source.find("orion")
                    .valueOrFailure("open repository")
                    .files().readBytes("refs/heads/main", "material.p12"));
            assertThat(source.find("orion").valueOrFailure("repository").refs()).containsKey("refs/heads/main");

            try (NativeGitRepositoryProvider incomplete = repositoryWith(
                    configuration, Map.of("orion.xml", backup.get("orion.xml")))) {
                assertThatThrownBy(() -> openContext(configuration, ENVIRONMENT, borrow(incomplete)))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Bootstrap inputs are unavailable or invalid");
                assertThat(new NativeGitKeyMaterialContentStore(
                        incomplete, "orion", "refs/heads/main", "material.p12").read()).isEmpty();

                try (NativeGitRepositoryProvider restored = repositoryWith(configuration, backup)) {
                    try (BootstrapContext runtime = openContext(configuration, ENVIRONMENT, borrow(restored))) {
                        assertThat(runtime.serverIdentity().activeKeyId()).isEqualTo("server-signing-v1");
                        assertThat(runtime.serverIdentity().verify("server-signing-v1", payload, signature)).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void resolvesRemoteConfigurationAndMaterialThroughOneHiddenProxy() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream upstream = upstream("shared", Map.of(
                "orion.xml", bytes("configuration"),
                "material.p12", materialBytes(configuration)));
        String location = "git+" + upstream.bare().toUri();
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getKeyMaterial().setLocation(location);
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(EVENTS.rawManager());
        try {
            try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
                String configurationRepository = context.repositoryFactory()
                        .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                        .orElseThrow();
                String materialRepository = context.repositoryFactory()
                        .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE)
                        .orElseThrow();

                assertThat(materialRepository).isEqualTo(configurationRepository);
                assertThat(backend.repositoryNames()).containsExactly(configurationRepository);
                assertThat(context.repositoryProvider().repositoryNames()).doesNotContain(configurationRepository);
            }
        } finally {
            upstream.git().close();
        }
    }

    @Test
    void supportsIndependentRemoteConfigurationAndMaterialRepositories() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream configurationUpstream = upstream(
                "configuration",
                Map.of("orion.xml", bytes("configuration")));
        Upstream materialUpstream = upstream(
                "material",
                Map.of("material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation(
                "git+" + configurationUpstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation(
                "git+" + materialUpstream.bare().toUri());
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(EVENTS.rawManager());
        try {
            try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
                String configurationRepository = context.repositoryFactory()
                        .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                        .orElseThrow();
                String materialRepository = context.repositoryFactory()
                        .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE)
                        .orElseThrow();

                assertThat(materialRepository).isNotEqualTo(configurationRepository);
                assertThat(backend.repositoryNames())
                        .containsExactlyInAnyOrder(configurationRepository, materialRepository);
                assertThat(context.repositoryProvider().repositoryNames()).isEmpty();
            }
        } finally {
            configurationUpstream.git().close();
            materialUpstream.git().close();
        }
    }

    @Test
    void selectsS3MetadataBackendAndPreservesFileDefault() {
        BootstrapConfiguration configuration = configuration();
        assertThat(new BootstrapConfiguration().getStorage().getLocation()).isEqualTo("file:orion/repos");
        assertThat(new BootstrapConfiguration().getStorage().getEndpoint()).isNull();
        try (S3Transport transport = new S3Transport()) {
            assertThat(
                            BootstrapContext.createRepositoryBackend(
                                    configuration, ENVIRONMENT, transport, EVENTS.rawManager()))
                    .isInstanceOf(
                            pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.class);
        }
        configuration.getStorage().setLocation("s3://bucket/repositories");
        configuration.getStorage().setAuth(Map.of("accessKeyId", "test", "secretAccessKey", "env:S3_SECRET"));
        try (S3Transport transport = new S3Transport();
             pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend backend =
                     BootstrapContext.createRepositoryBackend(
                             configuration, Map.of("S3_SECRET", "test"), transport, EVENTS.rawManager())) {
            assertThat(backend).isNotNull();
        }
    }

    @Test
    void validatesTheEndpointBoundFromYaml() throws Exception {
        Path yaml = tempDir.resolve("s3-invalid-endpoint.yml");
        Files.writeString(yaml, """
                storage:
                  location: s3://bucket/repositories
                  endpoint: file:/tmp/not-an-s3-endpoint
                  auth:
                    accessKeyId: test
                    secretAccessKey: env:S3_SECRET
                """);
        BootstrapConfiguration configuration = new BootstrapConfigurationReader(yaml.toString()).readConfiguration();
        assertThat(configuration.getStorage().getEndpoint()).isEqualTo("file:/tmp/not-an-s3-endpoint");
        assertThatThrownBy(() -> {
            try (S3Transport transport = new S3Transport();
                 pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend ignored =
                         BootstrapContext.createRepositoryBackend(
                                 configuration, Map.of("S3_SECRET", "test"), transport, EVENTS.rawManager())) {
                // Close the client if invalid endpoint validation regresses.
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S3 endpoint");
    }

    @Test
    void usesTheEndpointBoundFromYamlForS3Requests() throws Exception {
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        endpoint.createContext("/bucket/", exchange -> {
            requests.incrementAndGet();
            byte[] missing = "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(404, missing.length);
            exchange.getResponseBody().write(missing);
            exchange.close();
        });
        endpoint.start();
        try {
            String url = "http://127.0.0.1:" + endpoint.getAddress().getPort();
            Path yaml = tempDir.resolve("s3-endpoint.yml");
            Files.writeString(yaml, """
                    storage:
                      location: s3://bucket/repositories
                      endpoint: %s
                      auth:
                        region: eu-west-1
                        accessKeyId: test
                        secretAccessKey: env:S3_SECRET
                    """.formatted(url));
            BootstrapConfiguration configuration = new BootstrapConfigurationReader(yaml.toString())
                    .readConfiguration();
            assertThat(configuration.getStorage().getEndpoint()).isEqualTo(url);
            try (S3Transport transport = new S3Transport();
                 pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend backend =
                         BootstrapContext.createRepositoryBackend(
                                 configuration, Map.of("S3_SECRET", "test"), transport, EVENTS.rawManager())) {
                assertThat(backend.open(pro.deta.orion.schema.orion.v2.RepositoryName.parse("missing")))
                        .isInstanceOf(Result.Failure.class);
                assertThat(backend.exists(pro.deta.orion.schema.orion.v2.RepositoryName.parse("missing")))
                        .isFalse();
                assertThat(requests.get()).isEqualTo(2);
            }
            Files.writeString(yaml, Files.readString(yaml).replace("  endpoint: " + url + "\n", ""));
            configuration = new BootstrapConfigurationReader(yaml.toString()).readConfiguration();
            try (S3Transport transport = new S3Transport();
                 pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend ignored =
                         BootstrapContext.createRepositoryBackend(
                                 configuration, Map.of("S3_SECRET", "test"), transport, EVENTS.rawManager())) {
                assertThat(configuration.getStorage().getEndpoint()).isNull();
            }
        } finally {
            endpoint.stop(0);
        }
    }

    @Test
    void closesOwnedS3BackendWhenBootstrapFails() {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setPath("");
        NativeGitRepositoryProvider backend =
                S3NativeGitRepositoryFactory.repositories("s3://bucket/repositories", null,
                        Map.of("accessKeyId", "test", "secretAccessKey", "env:S3_SECRET"),
                        Map.of("S3_SECRET", "test"), EVENTS.rawManager());
        assertThatThrownBy(() -> openContext(configuration, ENVIRONMENT, backend))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Bootstrap source path must not be blank");
        assertThatThrownBy(backend::repositoryNames).hasMessageContaining("closed");
        backend.close();
    }

    @Test
    void rejectsUnsupportedRepositoryStorageOnFirstStart() {
        BootstrapConfiguration configuration = configuration();
        String location = "unsupported://bucket/repositories";
        configuration.getStorage().setLocation(location);

        assertThatThrownBy(() -> {
            try (BootstrapContext ignored = openContext(configuration, ENVIRONMENT, true)) {
                // Close material if an invalid configuration unexpectedly starts.
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported repository storage location: " + location);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingRepositoryStorageEnvironmentOnFirstStart(String directory) {
        BootstrapConfiguration configuration = configuration();
        String variable = "ORION_TEST_REPOSITORY_DIR";
        configuration.getStorage().setLocation("env:" + variable);
        Map<String, String> environment = new LinkedHashMap<>(ENVIRONMENT);
        if (directory != null) {
            environment.put(variable, directory);
        }

        assertThatThrownBy(() -> {
            try (BootstrapContext ignored = openContext(configuration, environment, true)) {
                // Close material if an invalid configuration unexpectedly starts.
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Environment variable " + variable + " is not set");
    }

    @Test
    void initializesEmptyDiskStorageAndRestartsWithTheSameIdentity() throws Exception {
        BootstrapConfiguration configuration = configuration();
        byte[] payload = bytes("first-start-identity");
        byte[] signature;
        String keyId;

        assertThatThrownBy(() -> openContext(configuration, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessage("Bootstrap source ref is unavailable: material");

        try (BootstrapContext initialized = openContext(configuration, ENVIRONMENT, true)) {
            keyId = initialized.serverIdentity().activeKeyId();
            signature = initialized.serverIdentity().sign(payload);
            OrionComponent component = runtimeComponent(configuration, initialized);
            try {
                assertThat(component.orionApplicationLifecycle().runApplication()).isEqualTo(RUNNING);
            } finally {
                component.orionApplicationLifecycle().shutdownApplication();
                EVENTS.close();
            }
        }

        try (BootstrapContext restarted = openContext(configuration, ENVIRONMENT)) {
            assertThat(restarted.serverIdentity().activeKeyId()).isEqualTo(keyId);
            assertThat(restarted.serverIdentity().verify(keyId, payload, signature)).isTrue();
            OrionComponent component = runtimeComponent(configuration, restarted);
            try {
                assertThat(component.orionApplicationLifecycle().runApplication()).isEqualTo(RUNNING);
            } finally {
                component.orionApplicationLifecycle().shutdownApplication();
                EVENTS.close();
            }
        }
    }

    @Test
    void createsMissingRepositoryMaterialBeforeRuntimeConstruction() throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));

        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend, true)) {
            byte[] material = backend.find("orion")
                    .valueOrFailure("open repository")
                    .files().readBytes("refs/heads/main", "material.p12");

            assertThat(context.serverIdentity().activeKeyId()).isNotBlank();
            assertThat(material).isNotEmpty();
        }
    }

    @Test
    void rejectsMissingRepositoryMaterialWithoutExplicitCreationRequest() throws Exception {
        BootstrapConfiguration configuration = configuration();
        try (NativeGitRepositoryProvider backend = repositoryWith(
                configuration, Map.of("orion.xml", bytes("configuration")))) {
            assertThatThrownBy(() -> openContext(configuration, ENVIRONMENT, borrow(backend)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap inputs are unavailable or invalid");
            assertThat(new NativeGitKeyMaterialContentStore(
                    backend, "orion", "refs/heads/main", "material.p12").read()).isEmpty();
        }
    }

    @Test
    void preservesSpecificKeyMaterialFailureAsCause() throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        assertThatThrownBy(() -> openContext(configuration, Map.of(), backend))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid")
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause()
                .hasMessage("Environment variable is not set: " + PASSWORD_ENV);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesExternalLocalConfigurationAndKeepsCommitHistory(boolean fileUri) throws Exception {
        BootstrapConfiguration configuration = configuration();
        Path directory = tempDir.resolve("external-acl.git");
        configuration.getBootstrap().getAccessControl().setLocation(
                fileUri ? directory.toUri().toString() : directory.toString());
        try (NativeGitRepositoryProvider backend = repositoryWith(configuration,
                Map.of("material.p12", materialBytes(configuration)))) {
            String firstRevision;
            try (BootstrapContext context = openContext(configuration, ENVIRONMENT, borrow(backend))) {
                assertThat(context.repositoryFactory()
                        .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)).isPresent();
                OrionVersionedConfigurationStorage storage =
                        new NativeGitOrionConfigurationStorage(
                                context.repositoryFactory(),
                                configuration.getBootstrap().getAccessControl());
                storage.save(OrionRevision.INITIAL, OrionXml.read(new ByteArrayInputStream(xml())),
                        new ChangeMetadata(UserEmail.EMPTY, "initial ACL"));
                OrionDocumentRevision first = storage.load().valueOrFailure("initial ACL");
                firstRevision = ((OrionRevision.Stored) first.revision()).value();
                OrionDocument updated =
                        first.document()
                                .replaceAccessControl(
                                        new AccessControl(
                                                List.of(
                                                        new User(
                                                                "updated-user",
                                                                null,
                                                                null,
                                                                null,
                                                                List.of(),
                                                                List.of(),
                                                                List.of())),
                                                List.of(),
                                                List.of()));
                storage.save(
                        first.revision(),
                        updated,
                        new ChangeMetadata(UserEmail.EMPTY, "update configuration"));
                assertThat(
                                ((OrionRevision.Stored)
                                                storage.load()
                                                        .valueOrFailure("updated ACL")
                                                        .revision())
                                        .value())
                        .isNotEqualTo(firstRevision);
                assertThatThrownBy(
                                () ->
                                        storage.save(
                                                first.revision(),
                                                first.document(),
                                                new ChangeMetadata(
                                                        UserEmail.EMPTY, "stale update")))
                        .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            }
            try (Git git = Git.open(directory.toFile())) {
                assertThat(git.log().add(git.getRepository().resolve("refs/heads/main")).call()).hasSize(2);
            }
            try (BootstrapContext reopened = openContext(configuration, ENVIRONMENT, borrow(backend))) {
                OrionVersionedConfigurationStorage storage =
                        new NativeGitOrionConfigurationStorage(
                                reopened.repositoryFactory(),
                                configuration.getBootstrap().getAccessControl());
                assertThat(
                                ((OrionRevision.Stored)
                                                storage.load()
                                                        .valueOrFailure("reopened ACL")
                                                        .revision())
                                        .value())
                        .isNotEqualTo(firstRevision);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file://other-host/acl.git", "file:/acl.git?ref=main", "file:/acl.git#main"})
    void rejectsAmbiguousExternalFileLocationsBeforeOpeningTheRepository(String location) {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation(location);
        assertBootstrapFailure(() -> openContext(configuration, ENVIRONMENT,
                NativeGitRepositoryProvider.inMemory(EVENTS.rawManager())));
    }

    @Test
    void rejectsMissingDirectConfigurationBeforeRuntimeConstruction() {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation(
                tempDir.resolve("configuration-root").toUri().toString());
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);

        assertBootstrapFailure(() -> openContext(
                configuration,
                ENVIRONMENT,
                NativeGitRepositoryProvider.inMemory(EVENTS.rawManager())));
    }

    @Test
    void publishesTheValidatedDirectConfigurationRoot() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Path baseDirectory = tempDir.toRealPath().resolve("runtime");
        Path configurationRoot = baseDirectory.resolve("configuration");
        Files.createDirectories(configurationRoot);
        seedExternalConfiguration(configurationRoot, bytes("configuration"));
        configuration.getBootstrap().setBaseDir(baseDirectory.toString());
        configuration.getBootstrap().getAccessControl().setLocation("configuration");
        configuration.getBootstrap().getAccessControl().setPath("./orion.xml");
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("material.p12", materialBytes(configuration)));

        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            var source = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE);

            assertThat(source).isPresent();
            assertThat(NativeGitRepositoryFactory.repositoryPath(
                    configuration.getBootstrap().getAccessControl().getPath())).isEqualTo("orion.xml");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false,false", "false,true", "true,false", "true,true"})
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    void rejectsDirectConfigurationSymlinks(boolean directoryLink, boolean dangling)
            throws Exception {
        BootstrapConfiguration configuration = configuration();
        Path root = Files.createDirectory(tempDir.resolve("configuration"));
        Path outside = tempDir.resolve("outside");
        if (!dangling) {
            Files.createDirectory(outside);
            Files.writeString(outside.resolve("orion.xml"), "configuration");
        }
        if (directoryLink) {
            Files.createSymbolicLink(root.resolve("config"), outside);
        } else {
            Files.createDirectory(root.resolve("config"));
            Files.createSymbolicLink(root.resolve("config/orion.xml"), outside.resolve("orion.xml"));
        }
        configuration.getBootstrap().getAccessControl().setLocation(root.toString());
        configuration.getBootstrap().getAccessControl().setPath("config/orion.xml");
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(true);
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration, Map.of("material.p12", materialBytes(configuration)));
        assertBootstrapFailure(() -> {
            try (BootstrapContext ignored = openContext(configuration, ENVIRONMENT, backend)) {
                // Successful bootstrap must fail the assertion, while still closing its resources.
            }
        });
    }

    @Test
    void rejectsUnsupportedDirectConfigurationBackendBeforeRuntimeConstruction() {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation("https://config.example/orion");

        assertBootstrapFailure(() -> openContext(
                configuration,
                ENVIRONMENT,
                NativeGitRepositoryProvider.inMemory(EVENTS.rawManager())));
    }

    @Test
    void rejectsWrongMaterialPasswordBeforeRuntimeConstruction() throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        assertBootstrapFailure(() -> openContext(
                configuration,
                Map.of(PASSWORD_ENV, "wrong-password"),
                backend));
    }

    @Test
    void opensAndReloadsExistingDirectMaterialFromItsExactLocationReference() throws Exception {
        BootstrapConfiguration configuration = configuration();
        try (NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")))) {
            Path materialPath = tempDir.resolve("existing-material.p12");
            Files.write(materialPath, materialBytes(configuration));
            makeOwnerOnly(materialPath);
            configuration.getBootstrap().getKeyMaterial().setLocation("env:ORION_TEST_MATERIAL_LOCATION");
            Map<String, String> environment = Map.of(
                    PASSWORD_ENV, "correct-password",
                    "ORION_TEST_MATERIAL_LOCATION", materialPath.toString());

            String activeKeyId;
            try (BootstrapContext context = openContext(configuration, environment, borrow(backend))) {
                activeKeyId = context.serverIdentity().activeKeyId();
            }
            try (BootstrapContext context = openContext(configuration, environment, borrow(backend))) {
                assertThat(context.serverIdentity().activeKeyId()).isEqualTo(activeKeyId);
            }
        }
    }

    @Test
    void rejectsInsecureDirectFileMaterialWithoutDisclosingItsPath() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));
        Path materialPath = tempDir.toRealPath().resolve("insecure-material.p12");
        Files.write(materialPath, materialBytes(configuration));
        Files.setPosixFilePermissions(materialPath, PosixFilePermissions.fromString("rw-r--r--"));
        configuration.getBootstrap().getKeyMaterial().setLocation(materialPath.toString());

        assertThatThrownBy(() -> openContext(configuration, ENVIRONMENT, backend))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid")
                .hasMessageNotContaining(materialPath.toString());
    }

    private static void makeOwnerOnly(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void keepsSharedBootstrapWithoutPersistingAProxyAlias() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream upstream = upstream("adoption", Map.of("orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        AdoptionStorage storage = new AdoptionStorage(xml());
        try (var ignored = upstream.git();
             BootstrapContext context = openContext(configuration, ENVIRONMENT,
                     NativeGitRepositoryProvider.inMemory(EVENTS.rawManager()))) {
            var source = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE);
            OrionDocument adopted = adopt(context, storage);
            assertThat(adopted.system().proxies()).isEmpty();
            assertThat(adopted.system().secrets()).isEmpty();
            assertThat(adopt(context, storage)).isEqualTo(adopted);
            assertThat(storage.saves).isZero();
            assertThat(context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE))
                    .isEqualTo(source);
            assertThat(context.repositoryProvider().repositoryNames()).isEmpty();
            assertThat(context.repositoryProvider().isPublicRepositoryName(source.orElseThrow()))
                    .isFalse();
            assertThat(storage.content).isEqualTo(xml());
        }
    }

    @Test
    void keepsBootstrapRevisionUnchangedAcrossRestart() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream upstream = upstream("durable-adoption", Map.of("orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        try (var ignored = upstream.git()) {
            OrionDocument adopted;
            OrionRevision adoptedRevision;
            try (BootstrapContext first = openContext(configuration, ENVIRONMENT,
                    NativeGitRepositoryProvider.inMemory(EVENTS.rawManager()))) {
                OrionVersionedConfigurationStorage storage =
                        new NativeGitOrionConfigurationStorage(
                                first.repositoryFactory(),
                                configuration.getBootstrap().getAccessControl());
                OrionRevision initialRevision = storage.load().valueOrFailure("configuration").revision();
                adopted = adopt(first, storage);
                adoptedRevision = storage.load().valueOrFailure("configuration").revision();
                assertThat(adopted.system().proxies()).isEmpty();
                assertThat(adoptedRevision).isEqualTo(initialRevision);
            }
            try (BootstrapContext restarted = openContext(configuration, ENVIRONMENT,
                    NativeGitRepositoryProvider.inMemory(EVENTS.rawManager()))) {
                OrionVersionedConfigurationStorage storage =
                        new NativeGitOrionConfigurationStorage(
                                restarted.repositoryFactory(),
                                configuration.getBootstrap().getAccessControl());
                assertThat(adopt(restarted, storage)).isEqualTo(adopted);
                assertThat(storage.load().valueOrFailure("configuration").revision()).isEqualTo(adoptedRevision);
                String cache = restarted.repositoryFactory()
                        .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
                assertThat(restarted.repositoryProvider().isPublicRepositoryName(cache)).isFalse();
            }
        }
    }

    @Test
    void reloadsAConcurrentWinnersAdoptionWithoutOverwritingIt() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.CONCURRENT_WINNER, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"material", "configuration"})
    void rechecksConfigurationWhenTheRepositoryAdvancesBeforeAdoptionSave(String changedFile) throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream upstream = upstream("revision-before-save", Map.of("orion.xml", xml()));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        NativeGitRepositoryProvider backend = repositoryWith(configuration,
                Map.of("material.p12", materialBytes(configuration)));
        Upstream materialUpstream = upstream("material-to-adopt", Map.of("material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + materialUpstream.bare().toUri());
        AdoptionStorage storage = new AdoptionStorage(xml());
        OrionDocumentRevision approved = approved(storage);
        AccessControl changedAcl = new AccessControl(List.of(new User(
                "concurrent-user", "", "", "", List.of(), List.of(), List.of())), List.of(), List.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(changedAcl), output);
        byte[] changedXml = output.toByteArray();
        storage.afterLoad = () -> {
            if ("configuration".equals(changedFile)) {
                storage.content = changedXml;
            }
            storage.version++;
        };
        try (Git ignored = upstream.git(); Git ignoredMaterial = materialUpstream.git();
             BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            Optional<OrionDocument> adopted = adoptApproved(context, storage, approved);
            if ("material".equals(changedFile)) {
                assertThat(adopted.orElseThrow().system().proxies()).hasSize(1);
                assertThat(storage.saves).isEqualTo(1);
                assertThat(OrionXml.read(new ByteArrayInputStream(storage.content))
                        .system().proxies()).hasSize(1);
            } else {
                assertThat(adopted).isEmpty();
                assertThat(storage.saves).isZero();
                assertThat(storage.content).isEqualTo(changedXml);
            }
            String cache = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
            assertThat(context.repositoryProvider().isPublicRepositoryName(cache)).isFalse();
        }
    }

    @Test
    void returnsSavedAdoptionWithoutReloadingConfiguration() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.FAIL_RELOAD, false);
    }

    @Test
    void recognizesASavedAdoptionAfterItsResponseWasLost() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.LOST_RESPONSE, true);
    }

    @Test
    void leavesConfigurationAndPrivateBindingsIntactAfterAFailedSave() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.FAIL_SAVE, false);
    }

    @Test
    void defersAdoptionOfANewerRevisionAndPreservesConcurrentConfigurationEdit() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.CONCURRENT_EDIT, true);
    }

    @Test
    void stopsAdoptionWhenTheConfigurationRevisionChanges() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.REPEATED_CONFLICT, false);
    }

    @Test
    void refusesAnUnversionedConfigurationBeforeSavingAdoption() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.UNVERSIONED, false);
    }

    @Test
    void validatesConfigurationBeforeSavingAdoption() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.INVALID_CONFIGURATION, false);
    }

    private void exerciseAdoptionSave(AdoptionStorage.Mode mode, boolean success) throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream upstream = upstream("transaction", Map.of("orion.xml", xml()));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        var backend = repositoryWith(configuration,
                Map.of("material.p12", materialBytes(configuration)));
        Upstream materialUpstream = upstream("material-to-adopt", Map.of("material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + materialUpstream.bare().toUri());
        AdoptionStorage storage = new AdoptionStorage(xml());
        storage.mode = mode == AdoptionStorage.Mode.INVALID_CONFIGURATION
                ? AdoptionStorage.Mode.NORMAL : mode;
        try (var ignored = upstream.git(); var ignoredMaterial = materialUpstream.git();
             BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            OrionDocumentRevision approved = approved(storage);
            storage.mode = mode;
            if (mode == AdoptionStorage.Mode.FAIL_RELOAD) {
                assertThat(adoptApproved(context, storage, approved)).isPresent();
                assertThat(storage.saves).isEqualTo(1);
                storage.mode = AdoptionStorage.Mode.NORMAL;
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
                assertThat(storage.saves).isEqualTo(1);
            } else if (mode == AdoptionStorage.Mode.CONCURRENT_WINNER
                    || mode == AdoptionStorage.Mode.LOST_RESPONSE
                    || mode == AdoptionStorage.Mode.CONCURRENT_EDIT
                    || mode == AdoptionStorage.Mode.REPEATED_CONFLICT) {
                assertThat(adoptApproved(context, storage, approved)).isEmpty();
                assertThat(storage.saves).isEqualTo(1);
                if (mode == AdoptionStorage.Mode.CONCURRENT_EDIT) {
                    assertThat(OrionXml.read(new ByteArrayInputStream(storage.content))
                            .system().accessControl().users()).extracting(User::id).contains("concurrent-user");
                }
                storage.mode = AdoptionStorage.Mode.NORMAL;
                OrionDocument retried = adopt(context, storage);
                assertThat(retried.system().proxies()).hasSize(1);
                if (mode == AdoptionStorage.Mode.CONCURRENT_EDIT) {
                    assertThat(retried.system().accessControl().users()).extracting(User::id)
                            .contains("concurrent-user");
                }
                assertThat(storage.saves).isEqualTo(mode == AdoptionStorage.Mode.CONCURRENT_EDIT
                        || mode == AdoptionStorage.Mode.REPEATED_CONFLICT ? 2 : 1);
            } else if (success) {
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
                assertThat(storage.saves).isEqualTo(1);
            } else {
                String message = switch (mode) {
                    case UNVERSIONED -> "requires a configuration revision";
                    case INVALID_CONFIGURATION -> "Configuration secret cannot be decrypted";
                    default -> "save failed";
                };
                assertThatThrownBy(() -> adoptApproved(context, storage, approved))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
                assertThat(OrionXml.read(new ByteArrayInputStream(storage.content))
                        .system().proxies()).isEmpty();
                int expectedSaves = switch (mode) {
                    case UNVERSIONED, INVALID_CONFIGURATION -> 0;
                    default -> 1;
                };
                assertThat(storage.saves).isEqualTo(expectedSaves);
                storage.mode = AdoptionStorage.Mode.NORMAL;
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
            }
            String cache = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
            assertThat(context.repositoryProvider().isPublicRepositoryName(cache)).isFalse();
            assertThat(context.repositoryProvider().repositoryNames()).doesNotContain(cache);
        }
    }

    private static byte[] xml() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), output);
        return output.toByteArray();
    }

    private static final class AdoptionStorage implements OrionVersionedConfigurationStorage {
        enum Mode {
            NORMAL, CONCURRENT_WINNER, CONCURRENT_EDIT, LOST_RESPONSE, FAIL_SAVE, FAIL_RELOAD,
            REPEATED_CONFLICT, UNVERSIONED, INVALID_CONFIGURATION
        }

        private byte[] content;
        private int version = 1;
        private int saves;
        private Mode mode = Mode.NORMAL;
        private Runnable afterLoad;

        private AdoptionStorage(byte[] xml) {
            content = xml;
        }

        @Override
        public Result<OrionDocumentRevision> load() {
            if (mode == Mode.FAIL_RELOAD && saves > 0) {
                return new Result.Failure<>(Result.FailureCode.GENERAL, "reload failed");
            }
            OrionRevision revision = mode == Mode.UNVERSIONED
                    ? OrionRevision.INITIAL : new OrionRevision.Stored(Integer.toString(version));
            OrionDocument document;
            try {
                document =
                        mode == Mode.INVALID_CONFIGURATION
                                ? new OrionDocument(
                                        new OrionDocument.SystemConfiguration(
                                                new AccessControl(),
                                                Optional.empty(),
                                                List.of(
                                                        new ConfigurationSecret(
                                                                "invalid", "not-an-envelope")),
                                                List.of(),
                                                List.of(),
                                                List.of()),
                                        List.of())
                                : OrionXml.read(new ByteArrayInputStream(content));
            } catch (IOException failure) {
                return new Result.Failure<>(Result.FailureCode.GENERAL, "invalid XML", failure);
            }
            OrionDocumentRevision snapshot = new OrionDocumentRevision(document, revision);
            Runnable callback = afterLoad;
            afterLoad = null;
            if (callback != null) {
                callback.run();
            }
            return new Result.Success<>(snapshot);
        }

        @Override
        public Result<OrionDocumentRevision> load(OrionRevision.Stored revision) {
            Result<OrionDocumentRevision> result = load();
            if (result instanceof Result.Success<OrionDocumentRevision> success
                    && success.value().revision().equals(revision)) return result;
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }

        @Override
        public OrionRevision save(OrionRevision expected, OrionDocument document, ChangeMetadata metadata) {
            assertThat(expected).isEqualTo(new OrionRevision.Stored(Integer.toString(version)));
            saves++;
            if (mode == Mode.FAIL_SAVE) {
                throw new IllegalStateException("save failed");
            }
            if (mode == Mode.REPEATED_CONFLICT) {
                version++;
                throw new OrionConfigurationConcurrentUpdateException("concurrent edit", null);
            }
            if (mode == Mode.CONCURRENT_EDIT) {
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    OrionDocument current = OrionXml.read(new ByteArrayInputStream(content));
                    OrionXml.write(current.replaceAccessControl(new AccessControl(
                            List.of(new User("concurrent-user", null, null, null, List.of(), List.of(), List.of())),
                            List.of(), List.of())), output);
                    content = output.toByteArray();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                version++;
                mode = Mode.NORMAL;
                throw new OrionConfigurationConcurrentUpdateException("concurrent edit", null);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                OrionXml.write(document, output);
                content = output.toByteArray();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            version++;
            if (mode == Mode.CONCURRENT_WINNER) {
                throw new OrionConfigurationConcurrentUpdateException("another bootstrap won", null);
            }
            if (mode == Mode.LOST_RESPONSE) {
                throw new IllegalStateException("response lost");
            }
            return new OrionRevision.Stored(Integer.toString(version));
        }

    }

    @Test
    void runtimeActivatesBootstrapWithoutCreatingAProxyAlias() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream upstream = upstream("runtime-adoption", Map.of("orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        try (var ignored = upstream.git();
             BootstrapContext context = openContext(configuration, ENVIRONMENT)) {
            var component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication())
                        .isEqualTo(RUNNING);
                var storage = new NativeGitOrionConfigurationStorage(context.repositoryFactory(),
                        configuration.getBootstrap().getAccessControl());
                var snapshot = storage.load().valueOrFailure("runtime configuration");
                assertThat(snapshot.document()
                        .system().proxies()).isEmpty();
                assertThatThrownBy(() -> context.repositoryFactory().adoptProvisional(
                        OrionDocument.withAccessControl(new AccessControl()), component.configurationSecrets()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
            } finally {
                lifecycle.shutdownApplication();
                EVENTS.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-xml", "missing-primary", "deleted-ref"})
    void rejectsInvalidHeadAtStartupWhenDefaultCreationIsDisabled(String change) throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(configuration, Map.of(
                "orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        NativeGitRepository repository = backend.find("orion").valueOrFailure("configuration repository");

        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            OrionComponent component = runtimeComponent(configuration, context);
            String approvedCommit = repository.refs().get("refs/heads/main");
            configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
            switch (change) {
                case "invalid-xml" ->
                        repository
                                .files()
                                .withAccess(
                                        "refs/heads/main",
                                        new pro.deta.orion.git.fileapi.GitCommitAuthor(
                                                "",
                                                "",
                                                "invalid update after bootstrap input load"),
                                        fileAccess -> {
                                            fileAccess.write("orion.xml", bytes("<not-valid-xml"));
                                            fileAccess.apply();
                                            return null;
                                        });
                case "missing-primary" ->
                        repository
                                .files()
                                .withAccess(
                                        "refs/heads/main",
                                        new pro.deta.orion.git.fileapi.GitCommitAuthor(
                                                "",
                                                "",
                                                "remove primary configuration after bootstrap input load"),
                                        fileAccess -> {
                                            fileAccess.delete("orion.xml");
                                            fileAccess.apply();
                                            return null;
                                        });
                case "deleted-ref" -> assertThat(repository.publishRefs(List.of(RefUpdate.fromWire(
                        "refs/heads/main", repository.refs().get("refs/heads/main"), "0".repeat(40))), true))
                        .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
                default -> throw new AssertionError(change);
            }
            String invalidCommit = repository.refs().get("refs/heads/main");
            assertThat(invalidCommit).isNotEqualTo(approvedCommit);

            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication()).isEqualTo(ERR);
                assertThat(component.orionAccessControlService().isRunning()).isFalse();
                assertThat(repository.refs().get("refs/heads/main")).isEqualTo(invalidCommit);
            } finally {
                lifecycle.shutdownApplication();
                EVENTS.close();
            }
        }
    }

    @Test
    void catchesUpToAValidConfigurationCommitAfterThePinnedBootstrapRead() throws Exception {
        BootstrapConfiguration configuration = configuration();
        NativeGitRepositoryProvider backend = repositoryWith(configuration, Map.of(
                "orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        NativeGitRepository repository = backend.find("orion").valueOrFailure("configuration repository");

        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            AccessControl acl = new AccessControl(List.of(new User(
                    "later-user", null, null, "later@example.test", List.of(), List.of(), List.of())),
                    List.of(), List.of());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OrionXml.write(OrionDocument.withAccessControl(acl), output);
            repository
                    .files()
                    .withAccess(
                            "refs/heads/main",
                            new pro.deta.orion.git.fileapi.GitCommitAuthor(
                                    "", "", "valid update after bootstrap input load"),
                            fileAccess -> {
                                fileAccess.write("orion.xml", output.toByteArray());
                                fileAccess.apply();
                                return null;
                            });

            OrionComponent component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                assertThat(component.orionAccessControlService().userExists("later-user")).isTrue();
            } finally {
                lifecycle.shutdownApplication();
                EVENTS.close();
            }
        }
    }

    @Test
    void retainsLastValidRemoteConfigurationThroughInvalidUpdateAndActivatesLaterRevision() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Upstream unrelatedUpstream = upstream("unrelated-proxy", Map.of("README", bytes("unrelated")));
        GitProxyBinding unrelated = new GitProxyBinding(new RemoteAlias("unrelated"),
                new GitProxyBinding.Direct(unrelatedUpstream.bare().toUri(), GitCredentialKind.NONE,
                        Optional.empty(), Optional.empty()), "main");
        OrionDocument first = new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(), Optional.empty(), List.of(), List.of(unrelated), List.of(),
                List.of()), List.of());
        ByteArrayOutputStream firstXml = new ByteArrayOutputStream();
        OrionXml.write(first, firstXml);
        Upstream upstream = upstream("deferred-bootstrap", Map.of(
                "orion.xml", firstXml.toByteArray(), "material.p12", materialBytes(configuration)));
        String location = "git+" + upstream.bare().toUri();
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getKeyMaterial().setLocation(location);
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(EVENTS.rawManager());

        try (Git ignored = upstream.git();
             Git ignoredUnrelated = unrelatedUpstream.git();
             BootstrapContext context = openContext(configuration, ENVIRONMENT, backend)) {
            String cache = context.repositoryFactory()
                    .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
            NativeGitRepository source = context.repositoryProvider().openForWrite(cache)
                    .valueOrFailure("remote configuration source");
            OrionComponent component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                source.files().withAccess("refs/heads/main",
                        new pro.deta.orion.git.fileapi.GitCommitAuthor("", "", "invalid B after activating A"),
                        fileAccess -> {
                            fileAccess.write("orion.xml", bytes("<not-valid-xml"));
                            fileAccess.apply();
                            return null;
                        });
                String invalidB = source.refs().get("refs/heads/main");

                EVENTS.await();
                assertThat(component.orionAccessControlService().configuration().document()).isEqualTo(first);
                assertThat(source.refs().get("refs/heads/main")).isEqualTo(invalidB);
                assertThat(context.repositoryProvider().isPublicRepositoryName(cache)).isFalse();

                assertThat(context.repositoryFactory().retry(
                        unrelated.alias(), () -> first, component.configurationSecrets()).isFailure()).isFalse();
                assertThat(source.refs().get("refs/heads/main")).isEqualTo(invalidB);

                AccessControl acl = new AccessControl(List.of(new User(
                        "later-user", null, null, "later@example.test", List.of(), List.of(), List.of())),
                        List.of(), List.of());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                OrionDocument valid = new OrionDocument(new OrionDocument.SystemConfiguration(
                        acl, Optional.empty(), List.of(), List.of(unrelated), List.of(),
                        List.of()), List.of());
                OrionXml.write(valid, output);
                source.files()
                        .withAccess(
                                "refs/heads/main",
                                new pro.deta.orion.git.fileapi.GitCommitAuthor(
                                        "", "", "valid C after invalid B"),
                                fileAccess -> {
                                    fileAccess.write("orion.xml", output.toByteArray());
                                    fileAccess.apply();
                                    return null;
                                });
                String validC = source.refs().get("refs/heads/main");
                assertThat(validC).isNotEqualTo(invalidB);
                EVENTS.await();
                assertThat(component.orionAccessControlService().userExists("later-user")).isTrue();
                assertThat(source.refs().get("refs/heads/main")).isEqualTo(validC);
            } finally {
                lifecycle.shutdownApplication();
                EVENTS.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runtimeKeepsAnExternalConfigurationRepositoryUsable(boolean remoteMaterial) throws Exception {
        BootstrapConfiguration configuration = configuration();
        Path directory = tempDir.resolve("plain-configuration");
        Files.createDirectories(directory);
        seedExternalConfiguration(directory, xml());
        configuration.getBootstrap().getAccessControl().setLocation(directory.toString());
        Upstream upstream = remoteMaterial
                ? upstream("remote-material", Map.of("material.p12", materialBytes(configuration))) : null;
        if (upstream != null) {
            configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        }
        try (var ignored = upstream == null ? null : upstream.git();
             BootstrapContext context = openContext(configuration, ENVIRONMENT, true)) {
            var component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication())
                        .isEqualTo(RUNNING);
                OrionVersionedConfigurationStorage local =
                        new NativeGitOrionConfigurationStorage(
                                context.repositoryFactory(),
                                configuration.getBootstrap().getAccessControl());
                assertThat(local.load().valueOrFailure("published configuration").document()
                        .system().proxies()).hasSize(remoteMaterial ? 1 : 0);
                assertThatThrownBy(() -> context.repositoryFactory().adoptProvisional(
                        OrionDocument.withAccessControl(new AccessControl()), component.configurationSecrets()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
            } finally {
                lifecycle.shutdownApplication();
                EVENTS.close();
            }
        }
    }

    @Test
    void invalidStoredSecretStopsStartupBeforeAgentAndPublicTransports() throws Exception {
        BootstrapConfiguration configuration = configuration();
        Path directory = tempDir.resolve("invalid-configuration");
        Files.createDirectories(directory);
        OrionDocument invalid = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.empty(), List.of(new ConfigurationSecret("bad", "invalid")),
                List.of(), List.of(),
                List.of()), List.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(invalid, output);
        seedExternalConfiguration(directory, output.toByteArray());
        configuration.getBootstrap().getAccessControl().setLocation(directory.toString());
        try (BootstrapContext context = openContext(configuration, ENVIRONMENT, true)) {
            var component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication())
                        .isEqualTo(ERR);
                for (String name : List.of("agent-session-server", "transports")) {
                    assertThat(component.runtimeStateMachine().childStatuses().get(name).state())
                            .isEqualTo(NEW);
                }
            } finally {
                lifecycle.shutdownApplication();
                EVENTS.close();
            }
        }
    }

    private static void seedExternalConfiguration(Path directory, byte[] content) throws Exception {
        Path worktree = directory.resolveSibling(directory.getFileName() + "-seed");
        try (Git git = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call()) {
            Files.write(worktree.resolve("orion.xml"), content);
            git.add().addFilepattern("orion.xml").call();
            git.commit().setMessage("initial configuration").setAuthor("Test", "test@example.test").call();
            try (Git bare = Git.cloneRepository().setURI(worktree.toUri().toString())
                    .setDirectory(directory.toFile()).setBare(true).call()) {
                assertThat(bare.getRepository().isBare()).isTrue();
            }
        }
    }

    private static OrionDocument adopt(BootstrapContext context, OrionVersionedConfigurationStorage storage) {
        return adoptApproved(context, storage, approved(storage)).orElseThrow();
    }

    private static Optional<OrionDocument> adoptApproved(
            BootstrapContext context,
            OrionVersionedConfigurationStorage storage,
            OrionDocumentRevision approved) {
        return BootstrapContext.adoptProxies(
                storage, new OrionConfigurationEditor(storage,
                new BootstrapConfiguration(), context.configurationCipher(), context.configurationMaterial()),
                context.repositoryFactory(), context.configurationCipher(), approved);
    }

    private static OrionDocumentRevision approved(OrionVersionedConfigurationStorage storage) {
        return storage.load().valueOrFailure("configuration for adoption");
    }

    private static OrionComponent runtimeComponent(
            BootstrapConfiguration configuration, BootstrapContext context) throws Exception {
        return DaggerOrionComponent.builder()
                .bootstrapConfiguration(configuration)
                .rootPasswordReset(new RootPasswordReset(false))
                .serverIdentityCapability(context.serverIdentity())
                .acmeKeyMaterialCapability(context.acmeKeyMaterial())
                .configurationMaterialCapability(context.configurationMaterial())
                .keyMaterialAdministrationCapability(context.keyMaterialAdministration())
                .eventManager(EVENTS.rawManager())
                .tlsCapability(context.tlsKeyMaterial())
                .sshHostKeyCapability(context.sshHostKeys(List.of()))
                .configurationCipherCapability(context.configurationCipher())
                .nativeGitRepositoryProvider(context.repositoryFactory())
                .configuredRepositoryFactory(context.storageFactory())
                .s3Transport(context.s3Transport())
                                .build();
    }

    private BootstrapConfiguration configuration() {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repositories").toUri().toString());
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttp().setEnabled(false);
        return configuration;
    }

    private static NativeGitRepositoryProvider repositoryWith(
            BootstrapConfiguration configuration,
            Map<String, byte[]> files) throws Exception {
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(EVENTS.rawManager());
        NativeGitRepository repository = backend.create("orion").valueOrFailure("create repository");
        repository
                .files()
                .withAccess(
                        configuration.getBootstrap().getAccessControl().selectedRef(),
                        new pro.deta.orion.git.fileapi.GitCommitAuthor(
                                "", "", "seed bootstrap inputs"),
                        fileAccess -> {
                            for (Map.Entry<String, byte[]> fileEntry : files.entrySet()) {
                                fileAccess.write(fileEntry.getKey(), fileEntry.getValue());
                            }
                            fileAccess.apply();
                            return null;
                        });
        return backend;
    }

    private static byte[] materialBytes(BootstrapConfiguration configuration) throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial ignored = OrionKeyMaterialFactory.open(
                configuration,
                ENVIRONMENT,
                store,
                new KeyMaterialCreation(true))) {
            // Generate the typed server identity in the test content store.
        }
        KeyMaterialSnapshot snapshot = store.read().orElseThrow();
        return snapshot.bytes();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Upstream upstream(String name, Map<String, byte[]> files) throws Exception {
        Path worktree = tempDir.resolve(name + "-worktree");
        Path bare = tempDir.resolve(name + "-remote.git");
        Git git = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Files.write(worktree.resolve(entry.getKey()), entry.getValue());
        }
        git.add().addFilepattern(".").call();
        git.commit().setMessage("bootstrap inputs").setAuthor("Test", "test@example.invalid").call();
        try (Git ignored = Git.cloneRepository()
                .setURI(worktree.toUri().toString())
                .setDirectory(bare.toFile())
                .setBare(true)
                .call()) {
            // Bare fixture is ready.
        }
        return new Upstream(git, bare);
    }

    private static void assertBootstrapFailure(ThrowingOpen open) {
        assertThatThrownBy(open::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid");
    }

    @FunctionalInterface
    private interface ThrowingOpen {
        void run() throws Exception;
    }

    private record Upstream(Git git, Path bare) {
    }
}
