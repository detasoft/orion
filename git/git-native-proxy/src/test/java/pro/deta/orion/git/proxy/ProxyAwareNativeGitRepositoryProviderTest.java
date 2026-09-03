package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyAwareNativeGitRepositoryProviderTest {
    @Test
    void refreshesProvisionalProxyBeforeEachLogicalRead() {
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(
                refreshes,
                new AtomicInteger());

        String repositoryName = provider.prepareProvisional(
                "configuration",
                remoteSource("orion.xml"));
        provider.openForRead(repositoryName).valueOrFailure("open proxy");
        provider.openForRead(repositoryName).valueOrFailure("open proxy");

        assertThat(refreshes).hasValue(3);
    }

    @Test
    void reusesCanonicalProxyAliasOnlyForMatchingAuthentication() {
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(
                refreshes,
                new AtomicInteger(),
                Map.of("BOOTSTRAP_TOKEN", "secret"));

        String configurationRepository = provider.prepareProvisional(
                "configuration",
                remoteHttpSource(
                        "git+https://EXAMPLE.test:443/orion.git",
                        "orion.xml",
                        "env:BOOTSTRAP_TOKEN"));
        String materialRepository = provider.prepareProvisional(
                "material",
                remoteHttpSource(
                        "git+https://example.test/orion.git",
                        "material.p12",
                        "env:BOOTSTRAP_TOKEN"));

        assertThat(materialRepository).isEqualTo(configurationRepository);
        assertThat(refreshes).hasValue(2);
    }

    @Test
    void rejectsConflictingAuthenticationWithoutReplacingTheOriginalBinding() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(
                new AtomicInteger(),
                new AtomicInteger(),
                Map.of(
                        "CONFIGURATION_TOKEN", "configuration-secret",
                        "MATERIAL_TOKEN", "material-secret"));
        BootstrapSourceConfig configuration = remoteHttpSource(
                "git+https://example.test/orion.git",
                "orion.xml",
                "env:CONFIGURATION_TOKEN");
        BootstrapSourceConfig conflicting = remoteHttpSource(
                "git+https://EXAMPLE.test:443/orion.git",
                "material.p12",
                "env:MATERIAL_TOKEN");
        String repositoryName = provider.prepareProvisional("configuration", configuration);

        assertThatThrownBy(() -> provider.prepareProvisional("material", conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap proxy binding configuration conflicts");
        assertThatThrownBy(() -> provider.prepareProvisional("configuration", conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap proxy binding configuration conflicts");

        assertThat(provider.provisionalRepositoryName("configuration")).isEqualTo(repositoryName);
        assertThatThrownBy(() -> provider.provisionalRepositoryName("material"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.prepareProvisional("configuration", configuration)).isEqualTo(repositoryName);
    }

    @Test
    void routesProxyFileSavesThroughUpstreamCompareAndSet() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger pushes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(
                refreshes,
                pushes);
        String repositoryName = provider.prepareProvisional(
                "configuration",
                remoteSource("orion.xml"));

        provider.saveFiles(
                repositoryName,
                "refs/heads/main",
                Map.of("orion.xml", "configuration".getBytes(StandardCharsets.UTF_8)),
                "initialize configuration",
                GitCommitAuthor.EMPTY);

        NativeGitRepository repository = provider.openForRead(repositoryName)
                .valueOrFailure("open local proxy");
        assertThat(pushes).hasValue(1);
        assertThat(refreshes).hasValue(3);
        assertThat(repository.loadFiles("refs/heads/main", List.of("orion.xml")).files())
                .containsEntry("orion.xml", "configuration".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void oneReadHandleRefreshesOnceAcrossMultipleRepositoryReads() {
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(refreshes, new AtomicInteger());
        String repositoryName = provider.prepareProvisional("configuration", remoteSource("orion.xml"));

        NativeGitRepository repository = provider.openForRead(repositoryName).valueOrFailure("open proxy");
        repository.refs();
        repository.readObject(GitObjectId.of("0".repeat(40)));
        repository.readObjectPrefix(GitObjectId.of("0".repeat(40)), 16);

        assertThat(refreshes).hasValue(2);
    }

    @Test
    void proxyHandleRejectsDirectObjectMutation() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), new AtomicInteger());
        String repositoryName = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.openForWrite(repositoryName).valueOrFailure("open proxy");

        assertThatThrownBy(() -> repository.writeObject(ObjectType.BLOB, new byte[]{1}))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.publishObjects(new LooseObjectStore()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void retainedProxyHandleRoutesSavesThroughProviderPublication() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), pushes);
        String repositoryName = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.find(repositoryName).valueOrFailure("find proxy alias");

        repository.saveFiles(
                "refs/heads/main",
                Map.of("orion.xml", "updated".getBytes(StandardCharsets.UTF_8)),
                "update configuration",
                GitCommitAuthor.EMPTY);

        assertThat(pushes).hasValue(1);
    }

    @Test
    void keepsOrdinaryLocalRepositoriesDirect() throws Exception {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        backend.create("local").valueOrFailure("create local");
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(
                backend,
                new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> refreshes.incrementAndGet(),
                (location, transport, repository, updates, atomic) ->
                        java.util.Collections.nCopies(updates.size(), true));

        provider.saveFiles(
                "local",
                "refs/heads/main",
                Map.of("file.txt", new byte[]{1}),
                "direct",
                GitCommitAuthor.EMPTY);

        assertThat(refreshes).hasValue(0);
        assertThat(provider.openForRead("local")).isNotNull();
    }

    @Test
    void activationAtomicallyReplacesPersistentBindingCatalog() {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        backend.create("persistent/first").valueOrFailure("first repository");
        backend.create("persistent/second").valueOrFailure("second repository");
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(
                backend,
                new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, updates, atomic) -> List.of());
        RecordingBinding first = new RecordingBinding();
        RecordingBinding second = new RecordingBinding();

        provider.activate(ignored -> Map.of("persistent/first", first), ignored -> new char[0]);
        provider.openForRead("persistent/first").valueOrFailure("first proxy");
        provider.activate(ignored -> Map.of("persistent/second", second), ignored -> new char[0]);
        provider.openForRead("persistent/first").valueOrFailure("direct first repository");
        provider.openForRead("persistent/second").valueOrFailure("second proxy");

        assertThat(first.refreshes).hasValue(1);
        assertThat(second.refreshes).hasValue(1);
    }

    @Test
    void activationRemovesUnadoptedProvisionalBindings() {
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(refreshes, new AtomicInteger());
        provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        String repositoryName = provider.provisionalRepositoryName("configuration");

        provider.activate(ignored -> Map.of(), ignored -> new char[0]);
        provider.openForRead(repositoryName).valueOrFailure("direct repository");

        assertThat(refreshes).hasValue(1);
    }

    private static ProxyAwareNativeGitRepositoryProvider provider(
            AtomicInteger refreshes,
            AtomicInteger pushes) {
        return provider(refreshes, pushes, Map.of());
    }

    private static ProxyAwareNativeGitRepositoryProvider provider(
            AtomicInteger refreshes,
            AtomicInteger pushes,
            Map<String, String> environment) {
        return new ProxyAwareNativeGitRepositoryProvider(
                new InMemoryNativeGitRepositoryProvider(),
                new BootstrapSecretResolver(environment),
                (location, transport, repository) -> refreshes.incrementAndGet(),
                (location, transport, repository, updates, atomic) -> {
                    pushes.incrementAndGet();
                    return java.util.Collections.nCopies(updates.size(), true);
                });
    }

    private static BootstrapSourceConfig remoteSource(String path) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("git+file:///upstream.git");
        source.setRef("refs/heads/main");
        source.setPath(path);
        source.setAuth(Map.of());
        return source;
    }

    private static BootstrapSourceConfig remoteHttpSource(
            String location,
            String path,
            String credentialReference) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation(location);
        source.setRef("refs/heads/main");
        source.setPath(path);
        source.setAuth(Map.of(
                "credentialKind", "http-bearer",
                "credential", credentialReference));
        return source;
    }

    private static final class RecordingBinding implements RuntimeGitProxyBinding {
        private final AtomicInteger refreshes = new AtomicInteger();

        @Override
        public void refresh() {
            refreshes.incrementAndGet();
        }

        @Override
        public List<RefUpdateResult> publish(
                LooseObjectStore objects,
                List<LooseRefStore.Update> updates,
                boolean atomic) {
            return java.util.Collections.nCopies(updates.size(), RefUpdateResult.STALE);
        }
    }
}
