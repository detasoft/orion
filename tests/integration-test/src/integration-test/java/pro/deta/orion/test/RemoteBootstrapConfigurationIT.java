package pro.deta.orion.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.schema.config.OrionConfiguration;
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
import java.util.Map;

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
            byte[] xml = upstream.accessControlService().accessControlConfigurationFile();
            byte[] material = materialBytes(target, environment);
            var repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("native bootstrap repository");
            repository.saveFiles("refs/heads/bootstrap",
                    Map.of("config/acl.xml", xml, "keys/server.p12", material),
                    "seed remote bootstrap inputs", GitCommitAuthor.EMPTY);
            target.getBootstrap().getAccessControl().setRef("refs/heads/bootstrap");
            target.getBootstrap().getAccessControl().setPath("config/acl.xml");
            target.getBootstrap().getKeyMaterial().setRef("refs/heads/bootstrap");
            target.getBootstrap().getKeyMaterial().setPath("keys/server.p12");

            Path processYaml = tempDir.resolve("process.yml");
            writeProcessYaml(processYaml, target);
            OrionConfiguration configuration = new LocationConfigurationProvider(processYaml.toUri().toString())
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
                assertThatThrownBy(() -> {
                    try (var ignored = BootstrapContext.open(configuration, environment)) {
                        throw new AssertionError("Unauthorized bootstrap must fail");
                    }
                }).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Bootstrap inputs are unavailable or invalid");
                assertPortsAvailable(configuration);
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
                            component.orionAccessControlService().accessControlConfigurationFile()));
                    var seeded = OrionXml.read(new ByteArrayInputStream(xml));
                    var seededAcl = seeded.system().accessControl();
                    var loadedAcl = loaded.system().accessControl();
                    assertThat(loadedAcl.getGrants()).isEqualTo(seededAcl.getGrants());
                    assertThat(loadedAcl.getRoles()).isEqualTo(seededAcl.getRoles());
                    assertThat(loadedAcl.getUsers()).singleElement().satisfies(root -> {
                        var seededRoot = seededAcl.getUsers().getFirst();
                        assertThat(root).usingRecursiveComparison().ignoringFields("credentials")
                                .isEqualTo(seededRoot);
                        assertThat(root.getCredentials()).containsAll(seededRoot.getCredentials());
                    });
                    var http = configuration.getTransport().getHttp();
                    assertThat(RuntimeHttpTestSupport.request("GET",
                            new URL("http", http.getAddress(), http.getPort(), "/api/admin/acl"), null).status())
                            .isEqualTo(403);
                    try (var ssh = new Socket()) {
                        ssh.connect(new InetSocketAddress(configuration.getTransport().getSsh().getAddress(),
                                configuration.getTransport().getSsh().getPort()), 2000);
                        assertThat(ssh.isConnected()).isTrue();
                    }
                } finally {
                    lifecycle.shutdownApplication();
                    lifecycle.waitForShutdown();
                }
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

    private static void writeProcessYaml(Path path, OrionConfiguration configuration) throws Exception {
        var bootstrap = configuration.getBootstrap();
        var acl = bootstrap.getAccessControl();
        var material = bootstrap.getKeyMaterial();
        var transport = configuration.getTransport();
        new ObjectMapper(new YAMLFactory()).writeValue(path.toFile(), Map.of(
                "bootstrap", Map.of(
                        "baseDir", bootstrap.getBaseDir(),
                        "accessControl", Map.of(
                                "location", acl.getLocation(), "ref", acl.getRef(), "paths", acl.selectedPaths(),
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

    private static void assertPortsAvailable(OrionConfiguration configuration) throws Exception {
        var transport = configuration.getTransport();
        for (int port : new int[]{transport.getHttp().getPort(), transport.getSsh().getPort(),
                transport.getGit().getPort()}) {
            try (var socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("localhost", port));
            }
        }
    }
}
