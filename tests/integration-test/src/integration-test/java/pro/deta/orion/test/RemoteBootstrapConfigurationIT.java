package pro.deta.orion.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.bootstrap.config.location.BootstrapConfigurationReader;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.PASSWORD_ENV;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.configureSources;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.materialBytes;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.runtimeComponent;

/**
 * Process YAML stays local; bootstrap.accessControl and bootstrap.keyMaterial select remote Git inputs.
 * Their auth maps reference external credential files, independently of the material password reference.
 */
class RemoteBootstrapConfigurationIT {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"http", "ssh"})
    void launchesFromProcessYamlAndRejectsUnauthorizedBootstrap(String transport) throws Exception {
        var upstreamConfiguration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("upstream"), config -> config.getTransport().getSsh().setEnabled(true));
        var target = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("target"), config -> {
            config.getTransport().getSsh().setEnabled(true);
            config.getTransport().getGit().setEnabled(true);
        });
        target.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
        target.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);

        try (var upstream = RuntimeHttpTestSupport.start(upstreamConfiguration)) {
            var environment = configureSources(tempDir, target, upstream, transport);
            byte[] xml = upstream.accessControlService().accessControlConfigurationFile().content();
            byte[] material = materialBytes(target, environment);
            var repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("native bootstrap repository");
            repository.files().withAccess("refs/heads/bootstrap", "seed remote bootstrap inputs",
                    GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("config/acl.xml", xml);
                fileAccess.write("keys/server.p12", material);
                fileAccess.apply();
                return null;
            });
            target.getBootstrap().getAccessControl().setRef("refs/heads/bootstrap");
            target.getBootstrap().getAccessControl().setPath("config/acl.xml");
            target.getBootstrap().getKeyMaterial().setRef("refs/heads/bootstrap");
            target.getBootstrap().getKeyMaterial().setPath("keys/server.p12");

            Path processYaml = tempDir.resolve("process.yml");
            writeProcessYaml(processYaml, target);
            BootstrapConfiguration configuration = new BootstrapConfigurationReader(processYaml.toUri().toString())
                    .readConfiguration();
            assertThat(configuration.getBootstrap().getBaseDir()).isEqualTo(target.getBootstrap().getBaseDir());
            assertThat(configuration.getTransport()).usingRecursiveComparison().isEqualTo(target.getTransport());
            assertThat(configuration.getBootstrap().getAccessControl()).usingRecursiveComparison()
                    .isEqualTo(target.getBootstrap().getAccessControl());
            assertThat(configuration.getBootstrap().getKeyMaterial()).usingRecursiveComparison()
                    .isEqualTo(target.getBootstrap().getKeyMaterial());

            Path credential = Path.of(URI.create(configuration.getBootstrap().getAccessControl()
                    .getAuth().get("credential")));
            String authorized = Files.readString(credential);
            Files.writeString(credential, unauthorizedCredential(transport));
            try {
                assertBootstrapRejected(configuration, environment, "unauthorized first launch");
            } finally {
                Files.writeString(credential, authorized);
            }

            var materialStore = new InMemoryKeyMaterialContentStore();
            materialStore.write(material, null);
            try (var seededMaterial = OrionKeyMaterialFactory.open(configuration, environment, materialStore, false);
                 var bootstrap = BootstrapContext.open(configuration, environment)) {
                byte[] payload = "remote material identity".getBytes(StandardCharsets.UTF_8);
                assertThat(bootstrap.serverIdentity().verify(seededMaterial.serverIdentity().activeKeyId(),
                        payload, seededMaterial.serverIdentity().sign(payload))).isTrue();
                var component = runtimeComponent(configuration, bootstrap);
                var lifecycle = component.orionApplicationLifecycle();
                try {
                    assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                    lifecycle.waitForStarting();
                    var loaded = OrionXml.read(new ByteArrayInputStream(
                            component.orionAccessControlService().accessControlConfigurationFile().content()));
                    var seeded = OrionXml.read(new ByteArrayInputStream(xml));
                    var seededAcl = seeded.system().accessControl();
                    var loadedAcl = loaded.system().accessControl();
                    assertThat(loadedAcl.grants()).isEqualTo(seededAcl.grants());
                    assertThat(loadedAcl.roles()).isEqualTo(seededAcl.roles());
                    assertThat(loadedAcl.users()).singleElement().satisfies(root -> {
                        var seededRoot = seededAcl.users().getFirst();
                        assertThat(root).usingRecursiveComparison().ignoringFields("credentials")
                                .isEqualTo(seededRoot);
                        assertThat(root.credentials()).containsAll(seededRoot.credentials());
                    });
                    var http = configuration.getTransport().getHttp();
                    assertThat(RuntimeHttpTestSupport.request("GET",
                            new URL("http", http.getAddress(), component.httpTransport().boundHttpPort(),
                                    "/api/admin/acl"), null).status())
                            .isEqualTo(403);
                    try (var ssh = new Socket()) {
                        ssh.connect(new InetSocketAddress(configuration.getTransport().getSsh().getAddress(),
                                component.sshTransport().boundPort()), 2000);
                        assertThat(ssh.isConnected()).isTrue();
                    }
                } finally {
                    lifecycle.shutdownApplication();
                    lifecycle.waitForShutdown();
                }
            }

            var refsBeforeFailures = repository.refs();
            for (var source : List.of(configuration.getBootstrap().getAccessControl(),
                    configuration.getBootstrap().getKeyMaterial())) {
                String ref = source.getRef();
                source.setRef("refs/heads/missing");
                try {
                    assertBootstrapRejected(configuration, environment, "missing ref for " + source.getPath());
                } finally {
                    source.setRef(ref);
                }
                String path = source.getPath();
                source.setPath("missing/input");
                try {
                    assertBootstrapRejected(configuration, environment, "missing path for " + path);
                } finally {
                    source.setPath(path);
                }
            }
            assertBootstrapRejected(configuration, Map.of(PASSWORD_ENV, "wrong-password"),
                    "wrong material password with a populated cache");
            Files.writeString(credential, unauthorizedCredential(transport));
            try {
                assertBootstrapRejected(configuration, environment, "unauthorized restart with a populated cache");
            } finally {
                Files.writeString(credential, authorized);
            }
            assertThat(repository.refs()).isEqualTo(refsBeforeFailures);
            try (var recovered = BootstrapContext.open(configuration, environment)) {
                assertThat(recovered.serverIdentity().activeKeyId()).isNotBlank();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "ssh"})
    void adoptsIndependentUpstreamsAndKeepsTheirWritesSeparateAcrossRestart(String configurationTransport)
            throws Exception {
        var configurationUpstream = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("configuration-upstream"), config -> config.getTransport().getSsh().setEnabled(true));
        var materialUpstream = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("material-upstream"), config -> config.getTransport().getSsh().setEnabled(true));
        var target = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("target"));
        target.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
        target.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        try (var configurationServer = RuntimeHttpTestSupport.start(configurationUpstream);
             var materialServer = RuntimeHttpTestSupport.start(materialUpstream)) {
            var environment = configureSources(Files.createDirectory(tempDir.resolve("configuration-auth")),
                    target, configurationServer, configurationTransport);
            var materialSource = new BootstrapConfiguration();
            configureSources(Files.createDirectory(tempDir.resolve("material-auth")), materialSource,
                    materialServer, "http".equals(configurationTransport) ? "ssh" : "http");
            var acl = target.getBootstrap().getAccessControl();
            var material = target.getBootstrap().getKeyMaterial();
            material.setLocation(materialSource.getBootstrap().getKeyMaterial().getLocation());
            material.setAuth(materialSource.getBootstrap().getKeyMaterial().getAuth());
            acl.setRef("refs/heads/configuration");
            material.setRef("refs/heads/keys");
            var configurationRepository = configurationServer.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("configuration upstream");
            var materialRepository = materialServer.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("material upstream");
            configurationRepository.files().withAccess(acl.getRef(), "seed configuration",
                    GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write(acl.getPath(),
                        configurationServer.accessControlService().accessControlConfigurationFile().content());
                fileAccess.apply();
                return null;
            });
            materialRepository.files().withAccess(material.getRef(), "seed material", GitCommitAuthor.EMPTY,
                    fileAccess -> {
                fileAccess.write(material.getPath(), materialBytes(target, environment));
                fileAccess.apply();
                return null;
            });

            String configurationRevision = null;
            String materialRevision = null;
            byte[] payload = "independent upstream identity".getBytes(StandardCharsets.UTF_8);
            byte[] signature = null;
            for (int launch = 0; launch < 2; launch++) {
                try (var bootstrap = BootstrapContext.open(target, environment)) {
                    var provider = bootstrap.repositoryProvider();
                    String configurationCache = bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                                    .orElseThrow();
                    String materialCache = bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE).orElseThrow();
                    assertThat(configurationCache).isNotEqualTo(materialCache);
                    var component = runtimeComponent(target, bootstrap);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                        lifecycle.waitForStarting();
                        byte[] xml = component.orionAccessControlService().accessControlConfigurationFile().content();
                        var document = OrionXml.read(new ByteArrayInputStream(xml));
                        assertThat(document.system().proxies()).extracting(binding -> binding.alias().value())
                                .containsExactly("material");
                        assertThat(document.system().proxies())
                                .extracting(binding -> binding.upstream(document.system()).toString())
                                .containsExactly(material.getLocation().substring(4));
                        assertThat(document.system().secrets()).hasSize(1);
                        for (var source : List.of(acl, material)) {
                            String credential = Files.readString(Path.of(URI.create(source.getAuth().get("credential"))));
                            assertThat(new String(xml, StandardCharsets.UTF_8)).doesNotContain(credential);
                        }
                        assertThat(provider.repositoryNames()).doesNotContain(configurationCache, materialCache);
                        assertThat(provider.isPublicRepositoryName(configurationCache)).isFalse();
                        assertThat(provider.isPublicRepositoryName(materialCache)).isFalse();
                        if (launch == 0) {
                            signature = bootstrap.serverIdentity().sign(payload);
                            var materialRefs = materialRepository.refs();
                            provider.openForWrite(configurationCache).valueOrFailure("configuration proxy")
                                    .files().withAccess(acl.getRef(), "write configuration upstream",
                                            GitCommitAuthor.EMPTY, fileAccess -> {
                                fileAccess.write("configuration-marker", payload);
                                fileAccess.apply();
                                return null;
                            });
                            assertThat(configurationRepository.files().readBytes(acl.getRef(),
                                    "configuration-marker")).isEqualTo(payload);
                            assertThat(materialRepository.refs()).isEqualTo(materialRefs);
                            var configurationRefs = configurationRepository.refs();
                            provider.openForWrite(materialCache).valueOrFailure("material proxy")
                                    .files().withAccess(material.getRef(), "write material upstream",
                                            GitCommitAuthor.EMPTY, fileAccess -> {
                                fileAccess.write("material-marker", payload);
                                fileAccess.apply();
                                return null;
                            });
                            assertThat(materialRepository.files().readBytes(material.getRef(),
                                    "material-marker")).isEqualTo(payload);
                            assertThat(configurationRepository.refs()).isEqualTo(configurationRefs);
                            configurationRevision = configurationRepository.refs().get(acl.getRef());
                            materialRevision = materialRepository.refs().get(material.getRef());
                        } else {
                            assertThat(bootstrap.serverIdentity().verify(bootstrap.serverIdentity().activeKeyId(),
                                    payload, signature)).isTrue();
                            assertThat(configurationRepository.refs()).containsEntry(acl.getRef(), configurationRevision);
                            assertThat(materialRepository.refs()).containsEntry(material.getRef(), materialRevision);
                        }
                    } finally {
                        lifecycle.shutdownApplication();
                        lifecycle.waitForShutdown();
                    }
                }
            }
        }
    }

    private static void assertBootstrapRejected(
            BootstrapConfiguration configuration, Map<String, String> environment, String scenario) throws Exception {
        int requestedPort = configuration.getTransport().getHttp().getPort();
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            configuration.getTransport().getHttp().setPort(occupiedPort.getLocalPort());
            try {
                assertThatThrownBy(() -> {
                    try (var ignored = BootstrapContext.open(configuration, environment)) {
                        throw new AssertionError("Invalid bootstrap must fail");
                    }
                }).as(scenario).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Bootstrap inputs are unavailable or invalid");
            } finally {
                configuration.getTransport().getHttp().setPort(requestedPort);
            }
        }
    }

    private static String unauthorizedCredential(String transport) throws Exception {
        if ("http".equals(transport)) {
            return "invalid-bearer-token";
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static void writeProcessYaml(Path path, BootstrapConfiguration configuration) throws Exception {
        var bootstrap = configuration.getBootstrap();
        var acl = bootstrap.getAccessControl();
        var material = bootstrap.getKeyMaterial();
        var transport = configuration.getTransport();
        new ObjectMapper(new YAMLFactory()).writeValue(path.toFile(), Map.of(
                "bootstrap", Map.of(
                        "baseDir", bootstrap.getBaseDir(),
                        "accessControl", Map.of(
                                "location", acl.getLocation(), "ref", acl.getRef(), "path", acl.getPath(),
                                "auth", acl.getAuth(), "createDefaultIfMissing", false),
                        "keyMaterial", Map.of(
                                "location", material.getLocation(), "ref", material.getRef(),
                                "path", material.getPath(), "auth", material.getAuth(),
                                "password", material.getPassword())),
                "storage", Map.of("location", configuration.getStorage().getLocation()),
                "transport", Map.of(
                        "http", Map.of("address", transport.getHttp().getAddress(),
                                "port", transport.getHttp().getPort(), "enabled", true),
                        "ssh", Map.of("address", transport.getSsh().getAddress(),
                                "port", transport.getSsh().getPort(), "enabled", true),
                        "git", Map.of("address", transport.getGit().getAddress(),
                                "port", transport.getGit().getPort(), "enabled", true))));
    }

}
