package pro.deta.orion.test;

import pro.deta.orion.git.fileapi.GitFileAccess;

import pro.deta.orion.git.fileapi.GitBlobStorage;

import pro.deta.orion.util.VcChangeDescription;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.config.BlobOrionConfigurationStorage;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
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
import java.util.Map;
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

    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

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
        configuration.getBootstrap().getConfiguration().setCreateDefaultIfMissing(false);

        var upstream = RuntimeHttpTestSupport.start(upstreamConfiguration);
        boolean stopped = false;
        try {
            Map<String, String> environment = configureSources(tempDir, configuration, upstream, transport);
            NativeGitRepository repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("upstream repository");
            byte[] originalConfiguration =
                    TestRuntimeBootstrap.configurationXml(upstream.component().configurationEditor());
            FileTestSupport.initialize(repository, REF);
            try (GitFileAccess fileAccess = repository.files().open(repository.ref(REF))) {
                fileAccess.write("orion.xml", originalConfiguration);
                fileAccess.write("material.p12", materialBytes(configuration, environment));
                repository.refUpdate(
                        repository.ref(REF),
                        fileAccess.revision(),
                        fileAccess.commit(
                                new VcChangeDescription(VcChangeAuthor.EMPTY, "bootstrap inputs")));
            }

            var upstreamProvider = NativeGitRepositoryFactory.bootstrap(
                    NativeGitRepositoryBackend.file(
                            new ConfigurationContext(upstreamConfiguration).getFileGitStoragePath(),
                            new pro.deta.orion.event.OrionEventManager()),
                    environment);
            String upstreamCache = upstreamProvider.prepareProvisional(
                    "cache-isolation-probe", configuration.getBootstrap().getConfiguration());
            assertCacheIsNotRoutable(configuration, environment, upstreamCache);
            try (var probeMaterial = TestServerIdentityMaterial.openMaterial(
                    configuration, environment,
                            testRuntime.pkcs12Store(environment.get(PASSWORD_ENV).toCharArray()))) {
                var probeConfiguration = new AtomicReference<>(OrionXml.read(
                        new ByteArrayInputStream(originalConfiguration)));
                var probeSecrets = new ConfigurationSecrets(probeConfiguration::get, probeMaterial);
                probeConfiguration.set(upstreamProvider.adoptProvisional(probeConfiguration.get(), probeSecrets));
                upstreamProvider.activate(probeConfiguration::get, probeSecrets);
                assertCacheIsNotRoutable(configuration, environment, upstreamCache);

                byte[] payload = bytes("bootstrap identity survives restart");
                byte[] signature;
                pro.deta.orion.event.OrionEventManager bootstrapEvents1 =
                        new pro.deta.orion.event.OrionEventManager();
                try (BootstrapContext bootstrap =
                        BootstrapContext.open(configuration, environment, bootstrapEvents1)) {
                    var provider = bootstrap.repositoryProvider();
                    String cache = bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
                    assertThat(bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE)).contains(cache);
                    assertThat(provider.repositoryNames()).doesNotContain(cache);
                    signature = bootstrap.keyMaterial().sign(bootstrap.signingMaterial().active(), payload);

                    NativeGitRepository retained = provider.openForRead(cache).valueOrFailure("provisional handle");

                    var storage = new BlobOrionConfigurationStorage(new GitBlobStorage(bootstrap.repositoryFactory().fileEvents(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE, configuration.getBootstrap().getConfiguration())), bootstrapEvents1);
                    var component = runtimeComponent(configuration, bootstrap, bootstrapEvents1);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication())
                                .isEqualTo(RUNNING);
                        var current =
                                new AtomicReference<>(
                                        storage.load()
                                                .result().valueOrFailure("Cannot read configuration document"));
                        assertThat(cache).isEqualTo("bootstrap");
                        assertThat(current.get().system().proxies()).isEmpty();
                        assertThat(current.get().system().secrets()).isEmpty();
                        assertThat(provider.repositoryNames()).doesNotContain(cache);
                        assertThat(provider.isPublicRepositoryName(cache)).isFalse();

                        ByteArrayOutputStream xml = new ByteArrayOutputStream();
                        OrionXml.write(current.get(), xml);
                        byte[] updatedConfiguration = bytes(xml.toString(StandardCharsets.UTF_8) + "\n");
                        FileTestSupport.initialize(repository, REF);
                        try (GitFileAccess fileAccess =
                                repository.files().open(repository.ref(REF))) {
                            fileAccess.write("orion.xml", updatedConfiguration);
                            repository.refUpdate(
                                    repository.ref(REF),
                                    fileAccess.revision(),
                                    fileAccess.commit(
                                            new VcChangeDescription(
                                                    VcChangeAuthor.EMPTY, "upstream edit")));
                        }

                        provider.openForRead(cache).valueOrFailure("refreshed proxy");
                        assertThat(retained.files().<byte[], Exception>read(retained.ref(REF), file -> file.readBytes("orion.xml")))
                                .isEqualTo(updatedConfiguration);

                        Path credentialFile = Path.of(URI.create(configuration.getBootstrap()
                                .getConfiguration().getAuth().get("credential")));
                        String external = Files.readString(credentialFile);
                        Files.writeString(credentialFile, "invalid-external-credential");
                        try {
                            assertThatThrownBy(() -> provider.openForRead(cache))
                                    .isInstanceOf(IllegalStateException.class);
                            assertThatThrownBy(
                                            () ->
                                                    BootstrapContext.open(
                                                            configuration,
                                                            environment,
                                                            new pro.deta.orion.event
                                                                    .OrionEventManager()))
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessage("Bootstrap inputs are unavailable or invalid");
                        } finally {
                            Files.writeString(credentialFile, external);
                        }

                        configureSources(tempDir, configuration, upstream, transport);
                        provider.openForRead(cache).valueOrFailure("rotated credential");

                        FileTestSupport.initialize(retained, REF);
                        try (GitFileAccess fileAccess = retained.files().open(retained.ref(REF))) {
                            fileAccess.write("marker.txt", bytes("proxy edit"));
                            retained.refUpdate(
                                    retained.ref(REF),
                                    fileAccess.revision(),
                                    fileAccess.commit(
                                            new VcChangeDescription(
                                                    VcChangeAuthor.EMPTY, "proxy edit")));
                        }

                        assertThat(
                                        repository
                                                .files()
                                                .<byte[], Exception>read(
                                                        repository.ref(REF),
                                                        file -> file.readBytes("marker.txt")))
                                .isEqualTo(bytes("proxy edit"));

                        var stale = FileTestSupport.prepared(retained, REF, "stale candidate",
                                VcChangeAuthor.EMPTY, fileAccess -> {
                            fileAccess.write("marker.txt", bytes("stale edit"));
                            return null;
                        });
                        FileTestSupport.initialize(repository, REF);
                        try (GitFileAccess fileAccess =
                                repository.files().open(repository.ref(REF))) {
                            fileAccess.write("marker.txt", bytes("concurrent upstream edit"));
                            repository.refUpdate(
                                    repository.ref(REF),
                                    fileAccess.revision(),
                                    fileAccess.commit(
                                            new VcChangeDescription(
                                                    VcChangeAuthor.EMPTY, "concurrent edit")));
                        }

                        String upstreamRevision = repository.refs().get(REF);
                        assertThat(provider.publishPack(cache, stale.pack(), stale.refUpdates(), true,
                                GitNativeRepositoryAccessHook.ALLOW_ALL))
                                .extracting(RefUpdateResult::status)
                                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
                        assertThat(repository.refs()).containsEntry(REF, upstreamRevision);
                        assertThat(
                                        repository
                                                .files()
                                                .<byte[], Exception>read(
                                                        repository.ref(REF),
                                                        file -> file.readBytes("marker.txt")))
                                .isEqualTo(bytes("concurrent upstream edit"));
                    } finally {
                        lifecycle.shutdownApplication();
                    }
                }

                pro.deta.orion.event.OrionEventManager bootstrapEvents2 =
                        new pro.deta.orion.event.OrionEventManager();
                try (BootstrapContext restarted =
                        BootstrapContext.open(configuration, environment, bootstrapEvents2)) {
                    assertThat(restarted.keyMaterial().verify(restarted.signingMaterial().active(),
                            payload, signature)).isTrue();
                    String cache = restarted.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
                    var provider = restarted.repositoryProvider();
                    var storage = new BlobOrionConfigurationStorage(new GitBlobStorage(restarted.repositoryFactory().fileEvents(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE, configuration.getBootstrap().getConfiguration())), bootstrapEvents2);
                    var before =
                            storage.load()
                                    .revision();
                    var component = runtimeComponent(configuration, restarted, bootstrapEvents2);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication())
                                .isEqualTo(RUNNING);
                        assertThat(
                                        storage.load()
                                                .revision())
                                .isEqualTo(before);
                        assertThat(provider.openForRead(cache)).isNotNull();
                        stopped = true;
                        upstream.close();
                        assertThatThrownBy(() -> restarted.repositoryProvider().openForRead(cache))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(
                                        () ->
                                                BootstrapContext.open(
                                                        configuration,
                                                        environment,
                                                        new pro.deta.orion.event
                                                                .OrionEventManager()))
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
        source.setLocation(configuration.getBootstrap().getConfiguration().getLocation()
                .replace("bootstrap-inputs.git", cache + ".git"));
        source.setAuth(configuration.getBootstrap().getConfiguration().getAuth());
        source.setPath("orion.xml");
        var client = NativeGitRepositoryFactory.bootstrap(
                NativeGitRepositoryBackend.inMemory(new pro.deta.orion.event.OrionEventManager()), environment);

        assertThatThrownBy(() -> client.resolveProvisional("guessed-cache", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote Git bootstrap failed during upstream discovery");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
