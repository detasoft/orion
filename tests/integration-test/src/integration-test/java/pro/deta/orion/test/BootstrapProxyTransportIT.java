package pro.deta.orion.test;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.util.ConfigurationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.PASSWORD_ENV;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.configureSources;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.materialBytes;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.runtimeComponent;
import pro.deta.orion.test.integration.git.FileTestSupport;

class BootstrapProxyTransportIT {
    private static final String REF = "refs/heads/main";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"http", "ssh"})
    void resolvesRefreshesAndPublishesThroughNativeUpstream(String transport) throws Exception {
        BootstrapConfiguration upstreamConfiguration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("upstream"), config -> config.getTransport().getSsh().setEnabled(true));
        BootstrapConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("proxy"));
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);

        var upstream = RuntimeHttpTestSupport.start(upstreamConfiguration);
        boolean stopped = false;
        try {
            Map<String, String> environment = configureSources(tempDir, configuration, upstream, transport);
            NativeGitRepository repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("upstream repository");
            byte[] originalConfiguration = upstream.accessControlService().accessControlConfigurationFile().content();
            repository.files().withAccess(REF, "bootstrap inputs", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("orion.xml", originalConfiguration);
                fileAccess.write("material.p12", materialBytes(configuration, environment));
                fileAccess.apply();
                return null;
            });

            var upstreamProvider = NativeGitRepositoryFactory.bootstrap(
                    NativeGitRepositoryBackend.file(
                            new ConfigurationContext(upstreamConfiguration).getFileGitStoragePath()),
                    environment);
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
                    String cache = bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
                    assertThat(bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE)).contains(cache);
                    assertThat(provider.repositoryNames()).doesNotContain(cache);
                    signature = bootstrap.serverIdentity().sign(payload);

                    NativeGitRepository retained = provider.openForRead(cache).valueOrFailure("provisional handle");

                    var storage = new NativeGitOrionConfigurationStorage(bootstrap.repositoryFactory(),
                            configuration.getBootstrap().getAccessControl());
                    var component = runtimeComponent(configuration, bootstrap);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication())
                                .isEqualTo(RUNNING);
                        var current = new AtomicReference<>(OrionXml.read(new ByteArrayInputStream(
                                storage.load().valueOrFailure("runtime configuration").content())));
                        assertThat(cache).isEqualTo("bootstrap");
                        assertThat(current.get().system().proxies()).isEmpty();
                        assertThat(current.get().system().secrets()).isEmpty();
                        assertThat(provider.repositoryNames()).doesNotContain(cache);
                        assertThat(provider.isPublicRepositoryName(cache)).isFalse();

                        ByteArrayOutputStream xml = new ByteArrayOutputStream();
                        OrionXml.write(current.get(), xml);
                        byte[] updatedConfiguration = bytes(xml.toString(StandardCharsets.UTF_8) + "\n");
                        repository.files().withAccess(REF, "upstream edit", GitCommitAuthor.EMPTY,
                                fileAccess -> {
                            fileAccess.write("orion.xml", updatedConfiguration);
                            fileAccess.apply();
                            return null;
                        });
                        provider.openForRead(cache).valueOrFailure("refreshed proxy");
                        assertThat(retained.files().readBytes(REF, "orion.xml"))
                                .isEqualTo(updatedConfiguration);

                        Path credentialFile = Path.of(URI.create(configuration.getBootstrap()
                                .getAccessControl().getAuth().get("credential")));
                        String external = Files.readString(credentialFile);
                        Files.writeString(credentialFile, "invalid-external-credential");
                        try {
                            assertThatThrownBy(() -> provider.openForRead(cache))
                                    .isInstanceOf(IllegalStateException.class);
                            assertThatThrownBy(() -> BootstrapContext.open(configuration, environment))
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessage("Bootstrap inputs are unavailable or invalid");
                        } finally {
                            Files.writeString(credentialFile, external);
                        }

                        configureSources(tempDir, configuration, upstream, transport);
                        provider.openForRead(cache).valueOrFailure("rotated credential");

                        retained.files().withAccess(REF, "proxy edit", GitCommitAuthor.EMPTY, fileAccess -> {
                            fileAccess.write("marker.txt", bytes("proxy edit"));
                            fileAccess.apply();
                            return null;
                        });
                        assertThat(repository.files().readBytes(REF, "marker.txt"))
                                .isEqualTo(bytes("proxy edit"));

                        var stale = FileTestSupport.prepared(retained.files(), REF, "stale candidate",
                                GitCommitAuthor.EMPTY, fileAccess -> {
                            fileAccess.write("marker.txt", bytes("stale edit"));
                            return null;
                        });
                        repository.files().withAccess(REF, "concurrent edit", GitCommitAuthor.EMPTY,
                                fileAccess -> {
                            fileAccess.write("marker.txt", bytes("concurrent upstream edit"));
                            fileAccess.apply();
                            return null;
                        });
                        String upstreamRevision = repository.refs().get(REF);
                        assertThat(provider.publishPack(cache, stale.pack(), stale.refUpdates(), true,
                                GitNativeRepositoryAccessHook.ALLOW_ALL))
                                .extracting(RefUpdateResult::status)
                                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
                        assertThat(repository.refs()).containsEntry(REF, upstreamRevision);
                        assertThat(repository.files().readBytes(REF, "marker.txt"))
                                .isEqualTo(bytes("concurrent upstream edit"));
                    } finally {
                        lifecycle.shutdownApplication();
                    }
                }

                try (BootstrapContext restarted = BootstrapContext.open(configuration, environment)) {
                    assertThat(restarted.serverIdentity().verify(
                            restarted.serverIdentity().activeKeyId(), payload, signature)).isTrue();
                    String cache = restarted.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
                    var provider = restarted.repositoryProvider();
                    var storage = new NativeGitOrionConfigurationStorage(restarted.repositoryFactory(),
                            configuration.getBootstrap().getAccessControl());
                    var before = storage.load().valueOrFailure("configuration before adoption").revision();
                    var component = runtimeComponent(configuration, restarted);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication())
                                .isEqualTo(RUNNING);
                        assertThat(storage.load().valueOrFailure("configuration after adoption").revision())
                                .isEqualTo(before);
                        assertThat(provider.openForRead(cache)).isNotNull();
                        stopped = true;
                        upstream.close();
                        assertThatThrownBy(() -> restarted.repositoryProvider().openForRead(cache))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> BootstrapContext.open(configuration, environment))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("Bootstrap inputs are unavailable or invalid");
                    } finally {
                        lifecycle.shutdownApplication();
                    }
                }
            }
        } finally {
            if (!stopped) {
                upstream.close();
            }
        }
    }

    private static void assertCacheIsNotRoutable(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            String cache) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation(configuration.getBootstrap().getAccessControl().getLocation()
                .replace("bootstrap-inputs.git", cache + ".git"));
        source.setAuth(configuration.getBootstrap().getAccessControl().getAuth());
        source.setPath("orion.xml");
        var client = NativeGitRepositoryFactory.bootstrap(
                NativeGitRepositoryBackend.inMemory(), environment);

        assertThatThrownBy(() -> client.resolveProvisional("guessed-cache", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote Git bootstrap failed during upstream discovery");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
