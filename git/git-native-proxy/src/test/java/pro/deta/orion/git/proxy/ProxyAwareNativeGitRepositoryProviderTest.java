package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.ReceivePackStatus;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyAwareNativeGitRepositoryProviderTest {
    @Test
    void passesPreparedPackUnchangedToTheUpstreamPusher() throws Exception {
        java.util.ArrayList<byte[]> forwarded = new java.util.ArrayList<>();
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(
                new InMemoryNativeGitRepositoryProvider(), new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) -> {
                    forwarded.add(received.packBytes());
                    return java.util.Collections.nCopies(updates.size(), true);
                });
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.openForWrite(name).valueOrFailure("repository");
        var prepared = repository.prepareFileUpdate("main", Map.of("orion.xml", new byte[]{1}),
                "prepared", GitCommitAuthor.EMPTY);

        ReceivePackStatus.requireSuccess(provider.publishPack(
                name, prepared.pack(), prepared.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL));

        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.getFirst()).isEqualTo(prepared.pack());
    }

    @Test
    void checksRefAccessBeforeForwardingAnInternalPack() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), pushes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.openForWrite(name).valueOrFailure("repository");
        var prepared = repository.prepareFileUpdate("refs/heads/main",
                Map.of("orion.xml", "updated".getBytes(StandardCharsets.UTF_8)), "update", GitCommitAuthor.EMPTY);
        Map<String, String> initialRefs = repository.refs();
        GitNativeRepositoryAccessHook denied = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeUpdate(String repositoryName, String refName, boolean force) {
                assertThat(repositoryName).isEqualTo(name);
                throw new AccessDeniedException("denied", null);
            }
        };

        assertThat(provider.publishPack(name, prepared.pack(), prepared.refUpdates(), true, denied))
                .containsExactly(new ReceivePackStatus("refs/heads/main", false, "ACCESS_DENIED"));
        assertThat(pushes).hasValue(0);
        assertThat(repository.refs()).isEqualTo(initialRefs);

        ReceivePackStatus.requireSuccess(provider.publishPack(
                name, prepared.pack(), prepared.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL));
        assertThat(pushes).hasValue(1);
    }

    @Test
    void keepsActiveBootstrapCacheInternalWhileOrdinaryNamesRemainPublic() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), new AtomicInteger());
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));

        assertThat(provider.isPublicRepositoryName(name)).isFalse();
        assertThat(provider.isPublicRepositoryName(name.replace("/", "%2F"))).isFalse();
        assertThat(provider.openForRead(name)).isInstanceOf(Result.Success.class);
        assertThat(provider.isPublicRepositoryName("team/repo")).isTrue();
        provider.activate(() -> Map.of());
        assertThat(provider.isPublicRepositoryName(name)).isFalse();
    }

    @Test
    void canonicalizesBeforeProxyBindingLookupAndBackendAccess() throws Exception {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        backend.create("team/repo").valueOrFailure("repository");
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        RuntimeGitProxyBinding binding = new RuntimeGitProxyBinding() {
            @Override
            public void refresh() {
                refreshes.incrementAndGet();
            }

            @Override
            public List<RefUpdateResult> publish(
                    PackIngestionResult.Complete received,
                    List<LooseRefStore.Update> updates,
                    boolean atomic) {
                publishes.incrementAndGet();
                return java.util.Collections.nCopies(updates.size(), RefUpdateResult.CREATED);
            }
        };
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(
                backend,
                new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) -> List.of());
        provider.activate(() -> Map.of("team/repo", binding));

        NativeGitRepository repository = provider.openForRead("team%2Frepo")
                .valueOrFailure("proxy repository");
        provider.saveFiles(
                "team%5Crepo",
                "refs/heads/main",
                Map.of("orion.xml", "configuration".getBytes(StandardCharsets.UTF_8)),
                "save through proxy",
                GitCommitAuthor.EMPTY);

        assertThat(repository.name()).isEqualTo("team/repo");
        assertThat(refreshes).hasValue(1);
        assertThat(publishes).hasValue(1);
    }

    @Test
    void canonicalizesLocalBootstrapRepositoryIdentity() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(
                new AtomicInteger(),
                new AtomicInteger());

        ResolvedBootstrapSource source = provider.resolveProvisional(
                "configuration",
                localSource("team%2Frepo"),
                true);

        assertThat(source.repositoryName()).contains("team/repo");
        assertThat(provider.exists("team/repo")).isTrue();
    }

    @Test
    void rejectsInvalidLocalBootstrapRepositoryNames() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(
                new AtomicInteger(),
                new AtomicInteger());

        for (String name : List.of(
                "/repo", "../repo", "%2E%2E/repo", "%GG", "Repo", "repo.git")) {
            assertThatThrownBy(() -> provider.resolveProvisional(
                    "source-" + Math.abs(name.hashCode()),
                    localSource(name),
                    true))
                    .as("local repository name %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

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
                (location, transport, repository, received, updates, atomic) ->
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
                (location, transport, repository, received, updates, atomic) -> List.of());
        RecordingBinding first = new RecordingBinding();
        RecordingBinding second = new RecordingBinding();

        provider.activate(() -> Map.of("persistent/first", first));
        provider.openForRead("persistent/first").valueOrFailure("first proxy");
        provider.activate(() -> Map.of("persistent/second", second));
        provider.openForRead("persistent/first").valueOrFailure("direct first repository");
        provider.openForRead("persistent/second").valueOrFailure("second proxy");

        assertThat(first.refreshes).hasValue(1);
        assertThat(second.refreshes).hasValue(1);
    }

    @Test
    void activationRejectsNonCanonicalPersistentBindingNames() {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        backend.create("team/repo").valueOrFailure("repository");
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(
                backend,
                new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) -> List.of());

        assertThatThrownBy(() -> provider.activate(
                () -> Map.of("team%2Frepo", new RecordingBinding())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not canonical");
    }

    @Test
    void activationMakesUnadoptedProvisionalCachesUnavailable() {
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(refreshes, new AtomicInteger());
        provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        String repositoryName = provider.provisionalRepositoryName("configuration");

        provider.activate(() -> Map.of());
        assertUnavailableCache(provider, repositoryName);

        assertThat(refreshes).hasValue(1);
    }

    @Test
    void retainedHandleCannotReadOrPublishAfterItsBootstrapBindingIsRemoved() {
        AtomicInteger pushes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), pushes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository retained = provider.openForWrite(name).valueOrFailure("proxy handle");
        provider.activate(() -> Map.of());

        assertThatThrownBy(retained::refs).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retained.saveFiles(
                "refs/heads/main", Map.of("orion.xml", new byte[]{1}), "save", GitCommitAuthor.EMPTY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retained.publishObjectsAndRefs(new LooseObjectStore(), List.of(), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(pushes).hasValue(0);
    }

    @Test
    void hidesCacheAfterProviderRestartAndAllowsBootstrapToRebindIt(@TempDir Path root) {
        NativeGitRepositoryProvider backend = new FileNativeGitRepositoryProvider(root);
        ProxyAwareNativeGitRepositoryProvider original = provider(backend);
        String name = original.prepareProvisional("configuration", remoteSource("orion.xml"));
        ProxyAwareNativeGitRepositoryProvider restarted = provider(new FileNativeGitRepositoryProvider(root));

        assertUnavailableCache(restarted, name);
        assertThat(restarted.prepareProvisional("configuration", remoteSource("orion.xml"))).isEqualTo(name);
        assertThat(restarted.openForRead(name)).isInstanceOf(Result.Success.class);
        assertThat(restarted.repositoryNames()).doesNotContain(name);
    }

    @Test
    void failedBootstrapRefreshLeavesNoAccessibleCache() {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(
                backend, new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { throw new IllegalStateException("upstream unavailable"); },
                (location, transport, repository, received, updates, atomic) -> List.of());
        BootstrapSourceConfig source = remoteSource("orion.xml");

        assertThatThrownBy(() -> provider.prepareProvisional("configuration", source))
                .isInstanceOf(BootstrapGitProxyException.class);
        String name = BootstrapGitLocation.parse(source).proxyName();
        assertThat(backend.exists(name)).isTrue();
        assertUnavailableCache(provider, name);
    }

    @Test
    void missingRequiredRefLeavesNoProvisionalBinding() {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        ProxyAwareNativeGitRepositoryProvider provider = provider(backend);
        BootstrapSourceConfig source = remoteSource("orion.xml");

        assertThatThrownBy(() -> provider.resolveProvisional("configuration", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source ref is unavailable: configuration");

        assertUnavailableCache(provider, BootstrapGitLocation.parse(source).proxyName());
        assertThatThrownBy(() -> provider.provisionalRepositoryName("configuration"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.resolveProvisional("configuration", source, true).revision()).isEmpty();
    }

    @Test
    void missingRequiredPathLeavesNoProvisionalBinding() throws Exception {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        BootstrapSourceConfig source = remoteSource("orion.xml");
        String name = BootstrapGitLocation.parse(source).proxyName();
        NativeGitRepository repository = backend.create(name).valueOrFailure("cache");
        repository.saveFiles("main", Map.of("other.xml", new byte[]{1}), "seed", GitCommitAuthor.EMPTY);
        ProxyAwareNativeGitRepositoryProvider provider = provider(backend);

        assertThatThrownBy(() -> provider.resolveProvisional("configuration", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: configuration");

        assertUnavailableCache(provider, name);
        assertThatThrownBy(() -> provider.provisionalRepositoryName("configuration"))
                .isInstanceOf(IllegalStateException.class);
        repository.saveFiles("main", Map.of("orion.xml", new byte[]{2}), "repair", GitCommitAuthor.EMPTY);
        assertThat(provider.resolveProvisional("configuration", source, false).revision()).isPresent();
    }

    @Test
    void failedSharedSourcePreservesPreviouslyResolvedBinding() throws Exception {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        BootstrapSourceConfig configuration = remoteSource("orion.xml");
        String name = BootstrapGitLocation.parse(configuration).proxyName();
        backend.create(name).valueOrFailure("cache").saveFiles(
                "main", Map.of("orion.xml", new byte[]{1}), "seed", GitCommitAuthor.EMPTY);
        ProxyAwareNativeGitRepositoryProvider provider = provider(backend);
        ResolvedBootstrapSource resolved = provider.resolveProvisional("configuration", configuration, false);
        NativeGitRepository retained = provider.openForRead(name).valueOrFailure("proxy handle");

        assertThatThrownBy(() -> provider.resolveProvisional("material", remoteSource("material.p12"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: material");

        assertThatThrownBy(() -> provider.provisionalRepositoryName("material"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.resolveProvisional(
                "configuration", remoteSource("missing.xml"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: configuration");
        assertThat(provider.provisionalRepositoryName("configuration")).isEqualTo(name);
        assertThat(retained.loadFiles("main", List.of("orion.xml")).version()).isEqualTo(resolved.revision());
        assertThat(provider.resolveProvisional("configuration", configuration, false)).isEqualTo(resolved);
    }

    private static void assertUnavailableCache(ProxyAwareNativeGitRepositoryProvider provider, String name) {
        for (String spelling : List.of(name, name.replace("/", "%2F"))) {
            assertThat(provider.exists(spelling)).isFalse();
            assertThat(provider.find(spelling)).isInstanceOf(Result.Failure.class);
            assertThat(provider.openForRead(spelling)).isInstanceOf(Result.Failure.class);
            assertThat(provider.openForWrite(spelling)).isInstanceOf(Result.Failure.class);
            assertThat(provider.create(spelling)).isEqualTo(new Result.Failure<>(
                    Result.FailureCode.NOT_SUPPORTED, "Bootstrap cache is internal"));
            assertThatThrownBy(() -> provider.saveFiles(
                    spelling, "refs/heads/main", Map.of("orion.xml", new byte[]{1}),
                    "save", GitCommitAuthor.EMPTY)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> provider.resolveProvisional("local", localSource(spelling), true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(provider.repositoryNames()).doesNotContain(name);
    }

    private static ProxyAwareNativeGitRepositoryProvider provider(NativeGitRepositoryProvider backend) {
        return new ProxyAwareNativeGitRepositoryProvider(
                backend, new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) ->
                        java.util.Collections.nCopies(updates.size(), true));
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
                (location, transport, repository, received, updates, atomic) -> {
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

    private static BootstrapSourceConfig localSource(String repositoryName) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("local:" + repositoryName);
        source.setRef("refs/heads/main");
        source.setPath("orion.xml");
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
                PackIngestionResult.Complete received,
                List<LooseRefStore.Update> updates,
                boolean atomic) {
            return java.util.Collections.nCopies(updates.size(), RefUpdateResult.STALE);
        }
    }
}
