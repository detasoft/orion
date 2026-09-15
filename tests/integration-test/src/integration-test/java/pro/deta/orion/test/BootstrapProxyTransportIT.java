package pro.deta.orion.test;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.ReceivePackStatus;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapProxyTransportIT {
    private static final String REF = "refs/heads/main";
    private static final String PASSWORD_ENV = "BOOTSTRAP_TEST_PASSWORD";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"http", "ssh"})
    void resolvesRefreshesAndPublishesThroughNativeUpstream(String transport) throws Exception {
        OrionConfiguration upstreamConfiguration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("upstream"), config -> config.getTransport().getSsh().setEnabled(true));
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("proxy"));
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);

        var upstream = RuntimeHttpTestSupport.start(upstreamConfiguration);
        boolean stopped = false;
        try {
            Map<String, String> environment = configureSources(configuration, upstream, transport);
            NativeGitRepository repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("upstream repository");
            byte[] originalConfiguration = upstream.accessControlService().accessControlConfigurationFile();
            repository.saveFiles(REF, Map.of(
                    "orion.xml", originalConfiguration,
                    "material.p12", materialBytes(configuration, environment)),
                    "bootstrap inputs", GitCommitAuthor.EMPTY);

            var upstreamProvider = (ProxyAwareNativeGitRepositoryProvider) upstream.repositoryProvider();
            String upstreamCache = upstreamProvider.prepareProvisional(
                    "cache-isolation-probe", configuration.getBootstrap().getAccessControl());
            assertCacheIsNotRoutable(configuration, environment, upstreamCache);
            try (var probeMaterial = OrionKeyMaterialFactory.open(configuration, environment,
                    new InMemoryKeyMaterialContentStore(), true)) {
                var probeConfiguration = new AtomicReference<>(OrionXml.read(
                        new ByteArrayInputStream(originalConfiguration)));
                var probeSecrets = new ConfigurationSecrets(probeConfiguration::get, probeMaterial.configurationCipher());
                probeConfiguration.set(upstreamProvider.adoptProvisional(probeConfiguration.get(), probeSecrets));
                upstreamProvider.activate(probeConfiguration::get, probeSecrets);
                assertCacheIsNotRoutable(configuration, environment, upstreamCache);

                byte[] payload = bytes("bootstrap identity survives restart");
                byte[] signature;
                try (BootstrapContext bootstrap = BootstrapContext.open(configuration, environment)) {
                    var provider = bootstrap.repositoryProvider();
                    String cache = bootstrap.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                            .repositoryName().orElseThrow();
                    assertThat(bootstrap.repositorySources().required(BootstrapRepositorySources.MATERIAL)
                            .repositoryName()).contains(cache);
                    assertThat(provider.repositoryNames()).doesNotContain(cache);
                    signature = bootstrap.serverIdentity().sign(payload);

                    NativeGitRepository retained = provider.openForRead(cache).valueOrFailure("provisional handle");

                    var storage = new AccessControlStorageResolver(bootstrap.repositorySources(), provider).resolve();
                    var current = new AtomicReference<>(bootstrap.adoptProxies(storage));
                    var secrets = new ConfigurationSecrets(current::get, bootstrap.configurationCipher());
                    provider.activate(current::get, secrets);
                    assertThat(current.get().system().proxies()).hasSize(1);
                    assertThat(current.get().system().secrets()).hasSize(1);
                    assertThat(provider.repositoryNames()).doesNotContain(cache);
                    assertThat(provider.isPublicRepositoryName(cache)).isFalse();

                    ByteArrayOutputStream xml = new ByteArrayOutputStream();
                    OrionXml.write(current.get(), xml);
                    byte[] updatedConfiguration = bytes(xml.toString(StandardCharsets.UTF_8) + "\n");
                    repository.saveFiles(REF, Map.of("orion.xml", updatedConfiguration),
                            "upstream edit", GitCommitAuthor.EMPTY);
                    provider.openForRead(cache).valueOrFailure("refreshed proxy");
                    assertThat(retained.loadFiles(REF, List.of("orion.xml")).files())
                            .containsEntry("orion.xml", updatedConfiguration);

                    Path credentialFile = Path.of(java.net.URI.create(configuration.getBootstrap()
                            .getAccessControl().getAuth().get("credential")));
                    String external = Files.readString(credentialFile);
                    Files.writeString(credentialFile, "invalid-external-credential");
                    try {
                        provider.openForRead(cache).valueOrFailure("stored credential");
                        assertThatThrownBy(() -> BootstrapContext.open(configuration, environment))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("Bootstrap inputs are unavailable or invalid");
                    } finally {
                        Files.writeString(credentialFile, external);
                    }

                    retained.saveFiles(REF, Map.of("marker.txt", bytes("proxy edit")),
                            "proxy edit", GitCommitAuthor.EMPTY);
                    assertThat(repository.loadFiles(REF, List.of("marker.txt")).files())
                            .containsEntry("marker.txt", bytes("proxy edit"));

                    var stale = retained.prepareFileUpdate(
                            REF, Map.of("marker.txt", bytes("stale edit")),
                            "stale candidate", GitCommitAuthor.EMPTY);
                    repository.saveFiles(REF, Map.of("marker.txt", bytes("concurrent upstream edit")),
                            "concurrent edit", GitCommitAuthor.EMPTY);
                    String upstreamRevision = repository.refs().get(REF);
                    assertThat(provider.publishPack(cache, stale.pack(), stale.refUpdates(), true,
                            GitNativeRepositoryAccessHook.ALLOW_ALL))
                            .extracting(ReceivePackStatus::ok).containsExactly(false);
                    assertThat(repository.refs()).containsEntry(REF, upstreamRevision);
                    assertThat(repository.loadFiles(REF, List.of("marker.txt")).files())
                            .containsEntry("marker.txt", bytes("concurrent upstream edit"));
                }

                try (BootstrapContext restarted = BootstrapContext.open(configuration, environment)) {
                    assertThat(restarted.serverIdentity().verify(
                            restarted.serverIdentity().activeKeyId(), payload, signature)).isTrue();
                    String cache = restarted.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                            .repositoryName().orElseThrow();
                    var provider = restarted.repositoryProvider();
                    var storage = new AccessControlStorageResolver(restarted.repositorySources(), provider).resolve();
                    var before = storage.load().valueOrFailure("configuration before adoption").version();
                    OrionDocument current = restarted.adoptProxies(storage);
                    assertThat(storage.load().valueOrFailure("configuration after adoption").version()).isEqualTo(before);
                    var secrets = new ConfigurationSecrets(() -> current, restarted.configurationCipher());
                    provider.activate(() -> current, secrets);
                    assertThat(provider.openForRead(cache)).isNotNull();
                    stopped = true;
                    upstream.close();
                    assertThatThrownBy(() -> restarted.repositoryProvider().openForRead(cache))
                            .isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(() -> BootstrapContext.open(configuration, environment))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Bootstrap inputs are unavailable or invalid");
                }
            }
        } finally {
            if (!stopped) {
                upstream.close();
            }
        }
    }

    private Map<String, String> configureSources(
            OrionConfiguration configuration,
            RuntimeHttpTestSupport.StartedOrion upstream,
            String transport) throws Exception {
        String location;
        String credential;
        Path credentialFile = tempDir.toRealPath().resolve("upstream-credential");
        String credentialReference = credentialFile.toUri().toString();
        Map<String, String> authentication;
        if ("http".equals(transport)) {
            credential = TestBearerTokens.issueRootToken(
                    upstream.accessControlService(), upstream.httpUrl("/api/admin/token"), 600);
            location = "git+" + upstream.httpUrl("/r/bootstrap-inputs.git");
            authentication = Map.of("credentialKind", "http-bearer", "credential", credentialReference);
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var key = generator.generateKeyPair();
            upstream.accessControlService().addKeyToUser("root", PublicKeyEntry.toString(key.getPublic()));
            credential = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            int port = upstream.configuration().getTransport().getSsh().getPort();
            Path knownHosts = tempDir.toRealPath().resolve("known_hosts");
            StringBuilder hosts = new StringBuilder();
            for (var hostKey : upstream.identity().sshHostKeys().keyPairs()) {
                hosts.append("[localhost]:").append(port).append(' ')
                        .append(PublicKeyEntry.toString(hostKey.getPublic())).append('\n');
            }
            Files.writeString(knownHosts, hosts);
            location = "git+ssh://root@localhost:" + port + "/bootstrap-inputs.git";
            authentication = Map.of("credentialKind", "ssh-private-key", "credential", credentialReference,
                    "knownHosts", knownHosts.toUri().toString());
        }
        Files.writeString(credentialFile, credential);
        if (Files.getFileStore(credentialFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(credentialFile, PosixFilePermissions.fromString("rw-------"));
        }
        for (BootstrapSourceConfig source : List.of(configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getKeyMaterial())) {
            source.setLocation(location);
            source.setAuth(authentication);
        }
        return Map.of(PASSWORD_ENV, "bootstrap-test-password");
    }

    private static byte[] materialBytes(OrionConfiguration configuration, Map<String, String> environment)
            throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (var material = OrionKeyMaterialFactory.open(configuration, environment, store, true)) {
            SshHostKeyLifecycle.open(material.sshHostKeyMaterial(), List.of());
        }
        return store.read().orElseThrow().bytes();
    }

    private static void assertCacheIsNotRoutable(
            OrionConfiguration configuration,
            Map<String, String> environment,
            String cache) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation(configuration.getBootstrap().getAccessControl().getLocation()
                .replace("bootstrap-inputs.git", cache + ".git"));
        source.setAuth(configuration.getBootstrap().getAccessControl().getAuth());
        source.setPath("orion.xml");
        var client = ProxyAwareNativeGitRepositoryProvider.bootstrap(
                new InMemoryNativeGitRepositoryProvider(), environment);

        assertThatThrownBy(() -> client.resolveProvisional("guessed-cache", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote Git bootstrap failed during upstream discovery");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
