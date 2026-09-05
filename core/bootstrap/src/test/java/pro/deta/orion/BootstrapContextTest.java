package pro.deta.orion;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialSnapshot;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    void createsMissingRepositoryMaterialBeforeRuntimeConstruction() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));

        try (BootstrapContext context = BootstrapContext.open(configuration, ENVIRONMENT, backend)) {
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
    void preservesSpecificKeyMaterialFailureAsCause() throws Exception {
        OrionConfiguration configuration = configuration();
        InMemoryNativeGitRepositoryProvider backend = repositoryWith(
                configuration,
                Map.of("orion.xml", bytes("configuration")));

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
                store)) {
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
