package pro.deta.orion.git.proxy;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.client.GitClientTransportException;
import java.util.concurrent.atomic.AtomicReference;
import static pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus.*;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelope;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
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
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.ReceivePackStatus;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.util.Result;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyAwareNativeGitRepositoryProviderTest {
    @Test
    void retriesOnlyTheSelectedAliasAndRetainsFailedBindingsForRecovery() {
        var visited = new java.util.ArrayList<String>();
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        var provider = new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider(),
                new BootstrapSecretResolver(Map.of()), (location, transport, repository) -> {
                    visited.add(location.remoteUri().getPath());
                    if (location.remoteUri().getPath().equals("/other.git") && unavailable.get()) {
                        throw new BootstrapGitProxyException("authentication", AUTHENTICATION_FAILED);
                    }
                }, (location, transport, repository, received, updates, atomic) -> List.of());
        OrionDocument initial = proxyDocument("configuration", "file:///upstream.git");
        var current = new AtomicReference<>(initial);
        provider.activate(current::get, secrets(initial));
        visited.clear();
        var added = proxyDocument("other", "file:///other.git").system().proxies().getFirst();
        current.set(new OrionDocument(new OrionDocument.SystemConfiguration(initial.system().accessControl(),
                Optional.empty(), List.of(), List.of(initial.system().proxies().getFirst(), added)), List.of()));

        assertThat(provider.retry(added.alias(), current::get, secrets(current.get())).status())
                .isEqualTo(AUTHENTICATION_FAILED);
        assertThat(provider.syncObservation(added).status()).isEqualTo(AUTHENTICATION_FAILED);
        unavailable.set(false);
        assertThat(provider.retry(added.alias(), current::get, secrets(current.get())).status()).isEqualTo(SUCCESS);
        assertThat(visited).containsExactly("/other.git", "/other.git");
        assertThat(provider.syncObservation(initial.system().proxies().getFirst()).status()).isEqualTo(SUCCESS);
        assertThat(provider.repositoryNames()).isEmpty();
    }

    @Test
    void observesActiveBindingsWithoutRefreshingOrPublishingAndTracksConfigurationIdentity() {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger pushes = new AtomicInteger();
        var provider = provider(refreshes, pushes);
        OrionDocument document = proxyDocument("configuration", "file:///upstream.git");
        GitProxyBinding binding = document.system().proxies().getFirst();
        assertThat(provider.syncObservation(binding).status()).isEqualTo(NOT_CHECKED);
        provider.activate(() -> document, secrets(document));
        refreshes.set(0);

        var observation = provider.syncObservation(binding);

        assertThat(observation.status()).isEqualTo(SUCCESS);
        assertThat(observation.observedAt()).isNotNull();
        assertThat(provider.syncObservation(binding)).isEqualTo(observation);
        assertThat(provider.syncObservation(
                proxyDocument("configuration", "file:///other.git").system().proxies().getFirst()).status())
                .isEqualTo(NOT_CHECKED);
        assertThat(refreshes).hasValue(0);
        assertThat(pushes).hasValue(0);
    }

    @Test
    void recordsSafeNativeAuthenticationFailureThenRecovery() {
        var failure = new AtomicReference<GitClientFailure>();
        var provider = new ProxyAwareNativeGitRepositoryProvider(
                new InMemoryNativeGitRepositoryProvider(), new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> {
                    if (failure.get() != null) {
                        new NativeBootstrapGitFetcher().fetch(location, (service, uri, options) -> {
                            throw new GitClientTransportException(
                                    failure.get().kind(), failure.get().retryable(), failure.get().message());
                        }, repository);
                    }
                }, (location, transport, repository, received, updates, atomic) -> List.of());
        OrionDocument document = proxyDocument("configuration", "file:///upstream.git");
        GitProxyBinding binding = document.system().proxies().getFirst();
        provider.activate(() -> document, secrets(document));
        failure.set(new GitClientFailure(GitClientFailure.Kind.AUTHENTICATION_FAILED,
                GitClientFailure.Phase.OPEN, false, "private upstream response with secret", null));
        String internal = BootstrapGitLocation.persistent(binding).proxyName();

        assertThatThrownBy(() -> provider.openForRead(internal))
                .hasMessageNotContaining("secret").hasNoCause();
        assertThat(provider.syncObservation(binding).status()).isEqualTo(AUTHENTICATION_FAILED);
        var failedAt = provider.syncObservation(binding).observedAt();
        assertThat(failedAt).isNotNull();

        failure.set(new GitClientFailure(GitClientFailure.Kind.TIMEOUT,
                GitClientFailure.Phase.OPEN, true, "private timeout details", null));
        assertThatThrownBy(() -> provider.openForRead(internal)).hasMessageNotContaining("private");
        assertThat(provider.syncObservation(binding).status()).isEqualTo(UNAVAILABLE);

        failure.set(null);
        provider.openForRead(internal).valueOrFailure("recovered");
        assertThat(provider.syncObservation(binding).status()).isEqualTo(SUCCESS);
        assertThat(provider.syncObservation(binding).observedAt()).isAfterOrEqualTo(failedAt);
    }

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
        activateAdopted(provider);
        assertThat(provider.isPublicRepositoryName(name)).isFalse();
    }

    @Test
    void canonicalizesBeforeProxyBindingLookupAndBackendAccess() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(refreshes, publishes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        activateAdopted(provider);
        refreshes.set(0);

        NativeGitRepository repository = provider.openForRead(name.replace("/", "%2F"))
                .valueOrFailure("proxy repository");
        provider.saveFiles(name.replace("/", "%5C"), "refs/heads/main",
                Map.of("orion.xml", "configuration".getBytes(StandardCharsets.UTF_8)),
                "save through proxy", GitCommitAuthor.EMPTY);

        assertThat(repository.name()).isEqualTo(name);
        assertThat(refreshes).hasValue(2);
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
    void activeProviderCannotReenterExternalBootstrapResolution() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), new AtomicInteger());
        provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        activateAdopted(provider);

        assertThatThrownBy(() -> provider.prepareProvisional("configuration", remoteSource("orion.xml")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
        assertThatThrownBy(() -> provider.resolveProvisional("local", localSource("ordinary"), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
    }

    @Test
    void activationAtomicallyReplacesPersistentBindingCatalog() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), new AtomicInteger());
        OrionDocument first = proxyDocument("first", "file:///first.git");
        OrionDocument second = proxyDocument("second", "file:///second.git");
        String firstName = BootstrapGitLocation.persistent(first.system().proxies().getFirst()).proxyName();
        String secondName = BootstrapGitLocation.persistent(second.system().proxies().getFirst()).proxyName();

        provider.activate(() -> first, secrets(first));
        assertThat(provider.openForRead(firstName)).isInstanceOf(Result.Success.class);
        provider.activate(() -> second, secrets(second));

        assertUnavailableCache(provider, firstName);
        assertThat(provider.openForRead(secondName)).isInstanceOf(Result.Success.class);
    }

    @Test
    void activationRejectsAnUnadoptedSourceAmongMultipleBindings() {
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), new AtomicInteger());
        String first = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        BootstrapSourceConfig material = remoteSource("material.p12");
        material.setLocation("git+file:///material.git");
        String second = provider.prepareProvisional("material", material);
        OrionDocument incomplete = proxyDocument("configuration", "file:///upstream.git");

        assertThatThrownBy(() -> provider.activate(() -> incomplete, secrets(incomplete)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("adopted");
        assertThat(provider.openForRead(first)).isInstanceOf(Result.Success.class);
        assertThat(provider.openForRead(second)).isInstanceOf(Result.Success.class);
        assertThat(provider.repositoryNames()).isEmpty();
    }

    @Test
    void removingAnAdoptedBindingMakesItsCacheUnavailable() {
        AtomicInteger refreshes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(refreshes, new AtomicInteger());
        provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        String repositoryName = provider.provisionalRepositoryName("configuration");

        activateAdopted(provider);
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        provider.activate(() -> empty, secrets(empty));
        assertUnavailableCache(provider, repositoryName);

        assertThat(refreshes).hasValue(2);
    }

    @Test
    void retainedHandleCannotReadOrPublishAfterItsBootstrapBindingIsRemoved() {
        AtomicInteger pushes = new AtomicInteger();
        ProxyAwareNativeGitRepositoryProvider provider = provider(new AtomicInteger(), pushes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository retained = provider.openForWrite(name).valueOrFailure("proxy handle");
        activateAdopted(provider);
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        provider.activate(() -> empty, secrets(empty));

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
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
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

    private static void activateAdopted(ProxyAwareNativeGitRepositoryProvider provider) {
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        ConfigurationSecrets secrets = secrets(empty);
        OrionDocument adopted = provider.adoptProvisional(empty, secrets);
        provider.activate(() -> adopted, secrets);
    }

    private static OrionDocument proxyDocument(String alias, String upstream) {
        var binding = new GitProxyBinding(new RemoteAlias(alias), URI.create(upstream), "main",
                GitProxyBinding.CredentialKind.NONE, Optional.empty(), Optional.empty(), Optional.empty());
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(),
                List.of(), List.of(binding)), List.of());
    }

    private static ConfigurationSecrets secrets(OrionDocument document) {
        return new ConfigurationSecrets(() -> document, new ConfigurationCipherCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                throw new AssertionError("File transport does not need a cipher");
            }

            @Override
            public ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context) {
                throw new AssertionError("File transport does not need a cipher");
            }

            @Override
            public byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context) {
                throw new AssertionError("File transport does not need a cipher");
            }
        });
    }
}
