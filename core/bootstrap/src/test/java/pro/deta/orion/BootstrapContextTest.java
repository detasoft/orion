package pro.deta.orion;

import org.eclipse.jgit.api.Git;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
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
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SigningKeyReferenceConfig;
import pro.deta.orion.schema.config.SshHostKeyReferenceConfig;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
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

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BootstrapContextTest {
    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";
    private static final Map<String, String> ENVIRONMENT = Map.of(PASSWORD_ENV, "correct-password");

    @TempDir
    private Path tempDir;

    @Test
    void opensConfigurationAndMaterialFromOneLocalRepository() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend)) {
            String configurationRepository = context.repositorySources()
                    .required(BootstrapRepositorySources.CONFIGURATION)
                    .repositoryName()
                    .orElseThrow();
            String materialRepository = context.repositorySources()
                    .required(BootstrapRepositorySources.MATERIAL)
                    .repositoryName()
                    .orElseThrow();

            assertThat(configurationRepository).isEqualTo("orion");
            assertThat(materialRepository).isEqualTo(configurationRepository);
            assertThat(context.repositoryProvider().repositoryNames()).containsExactly("orion");
            assertThat(context.serverIdentity().activeKeyId()).isNotBlank();
            assertThat(context.acmeKeyMaterial()).isNotNull();
            assertThat(context.tlsKeyMaterial()).isNotNull();
            assertThat(context.sshHostKeys().descriptors())
                    .extracting(descriptor -> descriptor.alias().value())
                    .containsExactly("ssh-host-ec-v1", "ssh-host-rsa-v1");
        }
    }

    @Test
    void rejectsAnUnresolvedExplicitSshHostKeyBeforeRuntimeConstruction() throws Exception {
        OrionConfiguration configuration = configuration();
        SshHostKeyReferenceConfig reference = new SshHostKeyReferenceConfig();
        reference.setAlias("missing-ssh-host-key");
        configuration.getTransport().getSsh().setHostKeys(List.of(reference));
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        assertThatThrownBy(() -> BootstrapContext.open(configuration, ENVIRONMENT, backend))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid")
                .rootCause()
                .hasMessageContaining("missing-ssh-host-key");
    }

    @Test
    void keepsExistingRuntimeWhenConfigurationReferencesUnstagedSigningMaterial() throws Exception {
        OrionConfiguration initial = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                initial,
                Map.of("orion.xml", bytes("configuration"), "material.p12", materialBytes(initial)));
        OrionConfiguration next = configuration();
        next.getBootstrap().getKeyMaterial().getServerSigning()
                .setActive(new SigningKeyReferenceConfig("server-signing-v2", 2));
        next.getBootstrap().getKeyMaterial().getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("server-signing-v1", 1)));
        byte[] payload = bytes("bootstrap-rotation");
        byte[] oldSignature;

        try (BootstrapContext current = BootstrapContext.open(initial, ENVIRONMENT, backend)) {
            oldSignature = current.serverIdentity().sign(payload);
            assertThatThrownBy(() -> BootstrapContext.open(next, ENVIRONMENT, backend))
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

            try (BootstrapContext activated = BootstrapContext.open(next, ENVIRONMENT, backend)) {
                assertThat(activated.serverIdentity().activeKeyId()).isEqualTo("server-signing-v2");
                assertThat(activated.serverIdentity().verify("server-signing-v1", payload, oldSignature)).isTrue();
            }
        }

        try (BootstrapContext restored = BootstrapContext.open(initial, ENVIRONMENT, backend)) {
            assertThat(restored.serverIdentity().activeKeyId()).isEqualTo("server-signing-v1");
            assertThat(restored.serverIdentity().verify("server-signing-v1", payload, oldSignature)).isTrue();
        }
    }

    @Test
    void doesNotRecreateLostMaterialForConfigurationWithRetainedIdentity() throws Exception {
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().getServerSigning()
                .setActive(new SigningKeyReferenceConfig("server-signing-v2", 2));
        configuration.getBootstrap().getKeyMaterial().getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("server-signing-v1", 1)));
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration, Map.of("orion.xml", bytes("configuration")));

        assertThatThrownBy(() -> BootstrapContext.open(configuration, ENVIRONMENT, backend))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid")
                .rootCause()
                .hasMessageContaining("Bootstrap source path is unavailable: material");
        assertThat(new NativeGitKeyMaterialContentStore(
                backend, "orion", "refs/heads/main", "material.p12").read()).isEmpty();
    }

    @Test
    void restoresPinnedConfigurationAndMaterialBytesWithoutChangingSigningIdentity() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider source = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration"), "material.p12", materialBytes(configuration)));
        byte[] payload = bytes("restored-identity");
        byte[] signature;
        try (BootstrapContext original = BootstrapContext.open(configuration, ENVIRONMENT, source)) {
            signature = original.serverIdentity().sign(payload);
        }
        GitRepositoryFileSnapshot backup = source.find("orion")
                .valueOrFailure("open repository")
                .loadFiles("refs/heads/main", List.of("orion.xml", "material.p12"));
        assertThat(backup.version()).isPresent();

        InMemoryNativeGitRepositoryProvider incomplete = repositoryWith(
                configuration, Map.of("orion.xml", backup.files().get("orion.xml")));
        assertThatThrownBy(() -> BootstrapContext.open(configuration, ENVIRONMENT, incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid");
        assertThat(new NativeGitKeyMaterialContentStore(
                incomplete, "orion", "refs/heads/main", "material.p12").read()).isEmpty();

        InMemoryNativeGitRepositoryProvider restored = repositoryWith(configuration, backup.files());
        try (BootstrapContext runtime = BootstrapContext.open(configuration, ENVIRONMENT, restored)) {
            assertThat(runtime.serverIdentity().activeKeyId()).isEqualTo("server-signing-v1");
            assertThat(runtime.serverIdentity().verify("server-signing-v1", payload, signature)).isTrue();
        }
    }

    @Test
    void resolvesRemoteConfigurationAndMaterialThroughOneHiddenProxy() throws Exception {
        OrionConfiguration configuration = configuration();
        Upstream upstream = upstream("shared", Map.of(
                "orion.xml", bytes("configuration"),
                "material.p12", materialBytes(configuration)));
        String location = "git+" + upstream.bare().toUri();
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getKeyMaterial().setLocation(location);
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        try {
            try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend)) {
                String configurationRepository = context.repositorySources()
                        .required(BootstrapRepositorySources.CONFIGURATION)
                        .repositoryName()
                        .orElseThrow();
                String materialRepository = context.repositorySources()
                        .required(BootstrapRepositorySources.MATERIAL)
                        .repositoryName()
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
        OrionConfiguration configuration = configuration();
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
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        try {
            try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend)) {
                String configurationRepository = context.repositorySources()
                        .required(BootstrapRepositorySources.CONFIGURATION)
                        .repositoryName()
                        .orElseThrow();
                String materialRepository = context.repositorySources()
                        .required(BootstrapRepositorySources.MATERIAL)
                        .repositoryName()
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
    void initializesEmptyDiskStorageAndRestartsWithTheSameIdentity() throws Exception {
        OrionConfiguration configuration = configuration();
        byte[] payload = bytes("first-start-identity");
        byte[] signature;
        String keyId;

        assertThatThrownBy(() -> BootstrapContext.open(configuration, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessage("Bootstrap source ref is unavailable: material");

        try (BootstrapContext initialized = BootstrapContext.open(configuration, ENVIRONMENT, true)) {
            keyId = initialized.serverIdentity().activeKeyId();
            signature = initialized.serverIdentity().sign(payload);
            OrionComponent component = runtimeComponent(configuration, initialized);
            try {
                assertThat(component.orionApplicationLifecycle().runApplication()).isEqualTo(RUNNING);
            } finally {
                component.orionApplicationLifecycle().shutdownApplication();
            }
        }

        try (BootstrapContext restarted = BootstrapContext.open(configuration, ENVIRONMENT)) {
            assertThat(restarted.serverIdentity().activeKeyId()).isEqualTo(keyId);
            assertThat(restarted.serverIdentity().verify(keyId, payload, signature)).isTrue();
            OrionComponent component = runtimeComponent(configuration, restarted);
            try {
                assertThat(component.orionApplicationLifecycle().runApplication()).isEqualTo(RUNNING);
            } finally {
                component.orionApplicationLifecycle().shutdownApplication();
            }
        }
    }

    @Test
    void createsMissingRepositoryMaterialBeforeRuntimeConstruction() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));

        try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend, true)) {
            byte[] material = backend.find("orion")
                    .valueOrFailure("open repository")
                    .loadFiles("refs/heads/main", java.util.List.of("orion.xml", "material.p12"))
                    .files()
                    .get("material.p12");

            assertThat(context.serverIdentity().activeKeyId()).isNotBlank();
            assertThat(material).isNotEmpty();
        }
    }

    @Test
    void rejectsMissingRepositoryMaterialWithoutExplicitCreationRequest() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration, Map.of("orion.xml", bytes("configuration")));

        assertThatThrownBy(() -> BootstrapContext.open(configuration, ENVIRONMENT, backend))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid");
        assertThat(new NativeGitKeyMaterialContentStore(
                backend, "orion", "refs/heads/main", "material.p12").read()).isEmpty();
    }

    @Test
    void preservesSpecificKeyMaterialFailureAsCause() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        assertThatThrownBy(() -> BootstrapContext.open(configuration, Map.of(), backend))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inputs are unavailable or invalid")
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause()
                .hasMessage("Environment variable is not set: " + PASSWORD_ENV);
    }

    @Test
    void rejectsMissingDirectConfigurationBeforeRuntimeConstruction() {
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation(
                tempDir.resolve("configuration-root").toUri().toString());
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);

        assertBootstrapFailure(() -> BootstrapContext.open(
                configuration,
                ENVIRONMENT,
                new InMemoryNativeGitRepositoryProvider()));
    }

    @Test
    void publishesTheValidatedDirectConfigurationRoot() throws Exception {
        OrionConfiguration configuration = configuration();
        Path baseDirectory = tempDir.toRealPath().resolve("runtime");
        Path configurationRoot = baseDirectory.resolve("configuration");
        Files.createDirectories(configurationRoot);
        Files.writeString(configurationRoot.resolve("orion.xml"), "configuration");
        configuration.getBootstrap().setBaseDir(baseDirectory.toString());
        configuration.getBootstrap().getAccessControl().setLocation("configuration");
        configuration.getBootstrap().getAccessControl().setPath("./orion.xml");
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("material.p12", materialBytes(configuration)));

        try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend)) {
            var source = context.repositorySources().required(BootstrapRepositorySources.CONFIGURATION);

            assertThat(source.repositoryName()).isEmpty();
            assertThat(source.location()).isEqualTo(configurationRoot.toUri().toString());
            assertThat(source.paths()).containsExactly("orion.xml");
        }
    }

    @Test
    void rejectsUnsupportedDirectConfigurationBackendBeforeRuntimeConstruction() {
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation("https://config.example/orion");

        assertBootstrapFailure(() -> BootstrapContext.open(
                configuration,
                ENVIRONMENT,
                new InMemoryNativeGitRepositoryProvider()));
    }

    @Test
    void rejectsWrongMaterialPasswordBeforeRuntimeConstruction() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of(
                        "orion.xml", bytes("configuration"),
                        "material.p12", materialBytes(configuration)));

        assertBootstrapFailure(() -> BootstrapContext.open(
                configuration,
                Map.of(PASSWORD_ENV, "wrong-password"),
                backend));
    }

    @Test
    void opensAndReloadsExistingDirectMaterialFromItsExactLocationReference() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));
        Path materialPath = tempDir.resolve("existing-material.p12");
        Files.write(materialPath, materialBytes(configuration));
        makeOwnerOnly(materialPath);
        configuration.getBootstrap().getKeyMaterial().setLocation("env:ORION_TEST_MATERIAL_LOCATION");
        Map<String, String> environment = Map.of(
                PASSWORD_ENV, "correct-password",
                "ORION_TEST_MATERIAL_LOCATION", materialPath.toString());

        String activeKeyId;
        try (BootstrapContext context = BootstrapContext.open(configuration, environment, backend)) {
            activeKeyId = context.serverIdentity().activeKeyId();
        }
        try (BootstrapContext context = BootstrapContext.open(configuration, environment, backend)) {
            assertThat(context.serverIdentity().activeKeyId()).isEqualTo(activeKeyId);
        }
    }

    @Test
    void rejectsInsecureDirectFileMaterialWithoutDisclosingItsPath() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));
        Path materialPath = tempDir.toRealPath().resolve("insecure-material.p12");
        Files.write(materialPath, materialBytes(configuration));
        Files.setPosixFilePermissions(materialPath, PosixFilePermissions.fromString("rw-r--r--"));
        configuration.getBootstrap().getKeyMaterial().setLocation(materialPath.toString());

        assertThatThrownBy(() -> BootstrapContext.open(configuration, ENVIRONMENT, backend))
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
    void persistsSharedProxyOnceAndKeepsExistingSourceHandlesOnRetry() throws Exception {
        OrionConfiguration configuration = configuration();
        Upstream upstream = upstream("adoption", Map.of("orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        AdoptionStorage storage = new AdoptionStorage(xml());
        try (var ignored = upstream.git();
             BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT,
                     new InMemoryNativeGitRepositoryProvider())) {
            var source = context.repositorySources().required(BootstrapRepositorySources.CONFIGURATION);
            OrionDocument adopted = adopt(context, storage);
            assertThat(adopted.system().proxies()).hasSize(1);
            assertThat(adopted.system().secrets()).isEmpty();
            assertThat(adopt(context, storage)).isEqualTo(adopted);
            assertThat(storage.saves).isEqualTo(1);
            assertThat(context.repositorySources().required(BootstrapRepositorySources.CONFIGURATION))
                    .isSameAs(source);
            assertThat(context.repositoryProvider().repositoryNames()).isEmpty();
            assertThat(context.repositoryProvider().isPublicRepositoryName(source.repositoryName().orElseThrow()))
                    .isFalse();
            assertThat(storage.files.get("extra.xml")).isEqualTo(xml());
        }
    }

    @Test
    void persistsAdoptionThroughNativeGitAndDoesNotRewriteItOnRestart() throws Exception {
        OrionConfiguration configuration = configuration();
        Upstream upstream = upstream("durable-adoption", Map.of("orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        try (var ignored = upstream.git()) {
            OrionDocument adopted;
            Optional<String> adoptedRevision;
            try (BootstrapContext first = BootstrapContext.open(configuration, ENVIRONMENT,
                    new InMemoryNativeGitRepositoryProvider())) {
                AccessControlStorage storage = new AccessControlStorageResolver(
                        first.repositorySources(), first.repositoryProvider()).resolve();
                Optional<String> initialRevision = storage.load().valueOrFailure("configuration").version();
                adopted = adopt(first, storage);
                adoptedRevision = storage.load().valueOrFailure("configuration").version();
                assertThat(adopted.system().proxies()).hasSize(1);
                assertThat(adoptedRevision).isNotEqualTo(initialRevision);
            }
            try (BootstrapContext restarted = BootstrapContext.open(configuration, ENVIRONMENT,
                    new InMemoryNativeGitRepositoryProvider())) {
                AccessControlStorage storage = new AccessControlStorageResolver(
                        restarted.repositorySources(), restarted.repositoryProvider()).resolve();
                assertThat(adopt(restarted, storage)).isEqualTo(adopted);
                assertThat(storage.load().valueOrFailure("configuration").version()).isEqualTo(adoptedRevision);
                String cache = restarted.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                        .repositoryName().orElseThrow();
                assertThat(restarted.repositoryProvider().isPublicRepositoryName(cache)).isFalse();
            }
        }
    }

    @Test
    void reloadsAConcurrentWinnersAdoptionWithoutOverwritingIt() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.CONCURRENT_WINNER, true);
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
    void retriesAgainstANewerRevisionAndPreservesConcurrentFileEdits() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.CONCURRENT_EDIT, true);
    }

    @Test
    void boundsAdoptionRetriesWhenTheConfigurationKeepsChanging() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.REPEATED_CONFLICT, false);
    }

    @Test
    void refusesAnUnversionedConfigurationBeforeSavingAdoption() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.UNVERSIONED, false);
    }

    @Test
    void validatesSecondaryConfigurationFilesBeforeSavingAdoption() throws Exception {
        exerciseAdoptionSave(AdoptionStorage.Mode.INVALID_SECONDARY, false);
    }

    private void exerciseAdoptionSave(AdoptionStorage.Mode mode, boolean success) throws Exception {
        OrionConfiguration configuration = configuration();
        Upstream upstream = upstream("transaction", Map.of("orion.xml", xml()));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        var backend = repositoryWith(configuration, Map.of("material.p12", materialBytes(configuration)));
        AdoptionStorage storage = new AdoptionStorage(xml());
        storage.mode = mode;
        try (var ignored = upstream.git();
             BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend)) {
            if (success) {
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
                assertThat(storage.saves).isEqualTo(mode == AdoptionStorage.Mode.CONCURRENT_EDIT ? 2 : 1);
                if (mode == AdoptionStorage.Mode.CONCURRENT_EDIT) {
                    assertThat(storage.files.get("concurrent.xml")).isEqualTo(xml());
                }
            } else {
                String message = switch (mode) {
                    case REPEATED_CONFLICT -> "kept changing";
                    case UNVERSIONED -> "requires a configuration revision";
                    case INVALID_SECONDARY -> "Cannot validate proxy configuration";
                    default -> "save failed";
                };
                assertThatThrownBy(() -> adopt(context, storage))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
                assertThat(OrionXml.read(new ByteArrayInputStream(storage.files.get("orion.xml")))
                        .system().proxies()).isEmpty();
                int expectedSaves = switch (mode) {
                    case UNVERSIONED, INVALID_SECONDARY -> 0;
                    case REPEATED_CONFLICT -> 3;
                    default -> 1;
                };
                assertThat(storage.saves).isEqualTo(expectedSaves);
                storage.mode = AdoptionStorage.Mode.NORMAL;
                assertThat(adopt(context, storage).system().proxies()).hasSize(1);
            }
            String cache = context.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                    .repositoryName().orElseThrow();
            assertThat(context.repositoryProvider().isPublicRepositoryName(cache)).isFalse();
            assertThat(context.repositoryProvider().repositoryNames()).doesNotContain(cache);
        }
    }

    private static byte[] xml() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), output);
        return output.toByteArray();
    }

    private static final class AdoptionStorage implements AccessControlStorage {
        enum Mode {
            NORMAL, CONCURRENT_WINNER, CONCURRENT_EDIT, LOST_RESPONSE, FAIL_SAVE,
            REPEATED_CONFLICT, UNVERSIONED, INVALID_SECONDARY
        }

        private Map<String, byte[]> files;
        private int version = 1;
        private int saves;
        private Mode mode = Mode.NORMAL;

        private AdoptionStorage(byte[] xml) {
            files = Map.of("orion.xml", xml, "extra.xml", xml);
        }

        @Override
        public Result<AccessControlSnapshot> load() {
            var revision = mode == Mode.UNVERSIONED
                    ? Optional.<String>empty() : Optional.of(Integer.toString(version));
            Map<String, byte[]> loaded = new LinkedHashMap<>(files);
            if (mode == Mode.INVALID_SECONDARY) {
                loaded.put("extra.xml", bytes("invalid"));
            }
            return new Result.Success<>(new AccessControlSnapshot(loaded, revision));
        }

        @Override
        public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
            assertThat(snapshot.version()).contains(Integer.toString(version));
            saves++;
            if (mode == Mode.FAIL_SAVE) {
                throw new IllegalStateException("save failed");
            }
            if (mode == Mode.REPEATED_CONFLICT) {
                version++;
                throw new AccessControlConcurrentUpdateException("concurrent edit", null);
            }
            if (mode == Mode.CONCURRENT_EDIT) {
                Map<String, byte[]> changed = new LinkedHashMap<>(files);
                changed.put("concurrent.xml", files.get("extra.xml"));
                files = Map.copyOf(changed);
                version++;
                mode = Mode.NORMAL;
                throw new AccessControlConcurrentUpdateException("concurrent edit", null);
            }
            files = snapshot.files();
            version++;
            if (mode == Mode.CONCURRENT_WINNER) {
                throw new AccessControlConcurrentUpdateException("another bootstrap won", null);
            }
            if (mode == Mode.LOST_RESPONSE) {
                throw new IllegalStateException("response lost");
            }
        }

        @Override
        public String primaryPath() {
            return "orion.xml";
        }
    }

    @Test
    void runtimeAdoptsAndActivatesTheResolvedRemoteSources() throws Exception {
        OrionConfiguration configuration = configuration();
        Upstream upstream = upstream("runtime-adoption", Map.of("orion.xml", xml(),
                "material.p12", materialBytes(configuration)));
        configuration.getBootstrap().getAccessControl().setLocation("git+" + upstream.bare().toUri());
        configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        try (var ignored = upstream.git();
             BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT)) {
            var component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication())
                        .isEqualTo(RUNNING);
                var storage = new AccessControlStorageResolver(context.repositorySources(),
                        context.repositoryProvider()).resolve();
                var snapshot = storage.load().valueOrFailure("runtime configuration");
                assertThat(OrionXml.read(new ByteArrayInputStream(snapshot.files().get("orion.xml")))
                        .system().proxies()).hasSize(1);
                assertThatThrownBy(() -> context.repositoryProvider().adoptProvisional(
                        OrionDocument.withAccessControl(new AccessControl()), component.configurationSecrets()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
            } finally {
                lifecycle.shutdownApplication();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runtimeKeepsAPlainConfigurationDirectoryUsable(boolean remoteMaterial) throws Exception {
        OrionConfiguration configuration = configuration();
        Path directory = tempDir.resolve("plain-configuration");
        Files.createDirectories(directory);
        Files.write(directory.resolve("orion.xml"), xml());
        configuration.getBootstrap().getAccessControl().setLocation(directory.toString());
        Upstream upstream = remoteMaterial
                ? upstream("remote-material", Map.of("material.p12", materialBytes(configuration))) : null;
        if (upstream != null) {
            configuration.getBootstrap().getKeyMaterial().setLocation("git+" + upstream.bare().toUri());
        }
        try (var ignored = upstream == null ? null : upstream.git();
             BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, true)) {
            var component = runtimeComponent(configuration, context);
            var lifecycle = component.orionApplicationLifecycle();
            try {
                assertThat(lifecycle.runApplication())
                        .isEqualTo(RUNNING);
                assertThat(OrionXml.read(new ByteArrayInputStream(Files.readAllBytes(
                        directory.resolve("orion.xml")))).system().proxies()).hasSize(remoteMaterial ? 1 : 0);
                assertThatThrownBy(() -> context.repositoryProvider().adoptProvisional(
                        OrionDocument.withAccessControl(new AccessControl()), component.configurationSecrets()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
            } finally {
                lifecycle.shutdownApplication();
            }
        }
    }

    @Test
    void invalidStoredSecretStopsStartupBeforeAgentAndPublicTransports() throws Exception {
        OrionConfiguration configuration = configuration();
        Path directory = tempDir.resolve("invalid-configuration");
        Files.createDirectories(directory);
        OrionDocument invalid = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.empty(), List.of(new ConfigurationSecret("bad", "invalid")),
                List.of()), List.of());
        try (var output = Files.newOutputStream(directory.resolve("orion.xml"))) {
            OrionXml.write(invalid, output);
        }
        configuration.getBootstrap().getAccessControl().setLocation(directory.toString());
        try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, true)) {
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
            }
        }
    }

    private static OrionDocument adopt(BootstrapContext context, AccessControlStorage storage) {
        return BootstrapContext.adoptProxies(storage, context.repositoryProvider(), context.configurationCipher());
    }

    private static OrionComponent runtimeComponent(
            OrionConfiguration configuration, BootstrapContext context) {
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .runtimeOptions(OrionRuntimeOptions.defaults())
                .serverIdentityCapability(context.serverIdentity())
                .acmeKeyMaterialCapability(context.acmeKeyMaterial())
                .tlsCapability(context.tlsKeyMaterial())
                .sshHostKeyCapability(context.sshHostKeys())
                .configurationCipherCapability(context.configurationCipher())
                .nativeGitRepositoryProvider(context.repositoryProvider())
                .bootstrapRepositorySources(context.repositorySources())
                .build();
    }

    private OrionConfiguration configuration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repositories").toUri().toString());
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttp().setEnabled(false);
        return configuration;
    }

    private static InMemoryNativeGitRepositoryProvider repositoryWith(
            OrionConfiguration configuration,
            Map<String, byte[]> files) throws Exception {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = backend.create("orion").valueOrFailure("create repository");
        repository.saveFiles(
                configuration.getBootstrap().getAccessControl().selectedRef(),
                files,
                "seed bootstrap inputs",
                GitCommitAuthor.EMPTY);
        return backend;
    }

    private static byte[] materialBytes(OrionConfiguration configuration) throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial ignored = OrionKeyMaterialFactory.open(
                configuration,
                ENVIRONMENT,
                store,
                true)) {
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
