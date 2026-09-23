package pro.deta.orion.test;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.util.ConfigurationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

class BootstrapProxyTransportIT {
    private static final String REF = "refs/heads/main";

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
            Map<String, String> environment = configureSources(tempDir, configuration, upstream, transport);
            NativeGitRepository repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("upstream repository");
            byte[] originalConfiguration = upstream.accessControlService().accessControlConfigurationFile();
            repository.saveFiles(REF, Map.of(
                    "orion.xml", GitFile.regular(originalConfiguration),
                    "material.p12", GitFile.regular(materialBytes(configuration, environment))), Set.of(),
                    "bootstrap inputs", GitCommitAuthor.EMPTY);

            var upstreamProvider = ProxyAwareNativeGitRepositoryProvider.bootstrap(
                    new FileNativeGitRepositoryProvider(
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
                    String cache = bootstrap.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                            .repositoryName().orElseThrow();
                    assertThat(bootstrap.repositorySources().required(BootstrapRepositorySources.MATERIAL)
                            .repositoryName()).contains(cache);
                    assertThat(provider.repositoryNames()).doesNotContain(cache);
                    signature = bootstrap.serverIdentity().sign(payload);

                    NativeGitRepository retained = provider.openForRead(cache).valueOrFailure("provisional handle");

                    var storage = new AccessControlStorageResolver(bootstrap.repositorySources(), provider).resolve();
                    var component = runtimeComponent(configuration, bootstrap);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication())
                                .isEqualTo(RUNNING);
                        var current = new AtomicReference<>(OrionXml.read(new ByteArrayInputStream(
                                storage.load().valueOrFailure("runtime configuration").files().get("orion.xml"))));
                        assertThat(current.get().system().proxies()).hasSize(1);
                        assertThat(current.get().system().secrets()).hasSize(1);
                        assertThat(provider.repositoryNames()).doesNotContain(cache);
                        assertThat(provider.isPublicRepositoryName(cache)).isFalse();

                        ByteArrayOutputStream xml = new ByteArrayOutputStream();
                        OrionXml.write(current.get(), xml);
                        byte[] updatedConfiguration = bytes(xml.toString(StandardCharsets.UTF_8) + "\n");
                        repository.saveFiles(REF, Map.of("orion.xml", GitFile.regular(updatedConfiguration)), Set.of(),
                                "upstream edit", GitCommitAuthor.EMPTY);
                        provider.openForRead(cache).valueOrFailure("refreshed proxy");
                        assertThat(retained.loadFiles(REF, List.of("orion.xml")).files())
                                .containsEntry("orion.xml", GitFile.regular(updatedConfiguration));

                        Path credentialFile = Path.of(URI.create(configuration.getBootstrap()
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

                        configureSources(tempDir, configuration, upstream, transport);
                        char[] replacement = Files.readString(credentialFile).toCharArray();
                        Files.writeString(credentialFile, external);
                        var rotated = component.configurationSecrets().replaceSystem(current.get(),
                                current.get().system().proxies().getFirst().secret().orElseThrow(), replacement);
                        assertThat(replacement).containsOnly('\0');
                        var beforeRotation = storage.load().valueOrFailure("before rotation");
                        var rotatedFiles = new LinkedHashMap<>(beforeRotation.files());
                        ByteArrayOutputStream rotatedXml = new ByteArrayOutputStream();
                        OrionXml.write(rotated, rotatedXml);
                        rotatedFiles.put(storage.primaryPath(), rotatedXml.toByteArray());
                        storage.save(new AccessControlSnapshot(
                                        rotatedFiles, beforeRotation.version()),
                                new AccessControlSaveRequest(
                                        "rotate proxy credential", UserEmail.EMPTY));
                        component.orionAccessControlService().reload("credential rotation");
                        current.set(rotated);
                        provider.openForRead(cache).valueOrFailure("rotated credential");

                        retained.saveFiles(REF, Map.of("marker.txt", GitFile.regular(bytes("proxy edit"))), Set.of(),
                                "proxy edit", GitCommitAuthor.EMPTY);
                        assertThat(repository.loadFiles(REF, List.of("marker.txt")).files())
                                .containsEntry("marker.txt", GitFile.regular(bytes("proxy edit")));

                        var stale = retained.prepareFileUpdate(
                                REF, Map.of("marker.txt", GitFile.regular(bytes("stale edit"))), Set.of(),
                                "stale candidate", GitCommitAuthor.EMPTY);
                        repository.saveFiles(REF,
                                Map.of("marker.txt", GitFile.regular(bytes("concurrent upstream edit"))), Set.of(),
                                "concurrent edit", GitCommitAuthor.EMPTY);
                        String upstreamRevision = repository.refs().get(REF);
                        assertThat(provider.publishPack(cache, stale.pack(), stale.refUpdates(), true,
                                GitNativeRepositoryAccessHook.ALLOW_ALL))
                                .extracting(RefUpdateResult::status)
                                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
                        assertThat(repository.refs()).containsEntry(REF, upstreamRevision);
                        assertThat(repository.loadFiles(REF, List.of("marker.txt")).files())
                                .containsEntry("marker.txt",
                                        GitFile.regular(bytes("concurrent upstream edit")));
                    } finally {
                        lifecycle.shutdownApplication();
                    }
                }

                try (BootstrapContext restarted = BootstrapContext.open(configuration, environment)) {
                    assertThat(restarted.serverIdentity().verify(
                            restarted.serverIdentity().activeKeyId(), payload, signature)).isTrue();
                    String cache = restarted.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                            .repositoryName().orElseThrow();
                    var provider = restarted.repositoryProvider();
                    var storage = new AccessControlStorageResolver(restarted.repositorySources(), provider).resolve();
                    var before = storage.load().valueOrFailure("configuration before adoption").version();
                    var component = runtimeComponent(configuration, restarted);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication())
                                .isEqualTo(RUNNING);
                        assertThat(storage.load().valueOrFailure("configuration after adoption").version()).isEqualTo(before);
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
