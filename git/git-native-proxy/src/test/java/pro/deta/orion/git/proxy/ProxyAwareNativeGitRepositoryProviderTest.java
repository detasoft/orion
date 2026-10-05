package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.client.GitClientTransportException;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelope;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.schema.orion.v2.GitCredentialKind;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.RemoteAlias;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.proxy.NativeGitRepositoryFactory.SyncStatus.*;
import pro.deta.orion.test.integration.git.FileTestSupport;

class ProxyAwareNativeGitRepositoryProviderTest {
    @Test
    void bootstrapKeepsItsNameAndInstanceIndependentlyOfUiProxies() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), pushes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository bootstrap = provider.provider().openForWrite(name).valueOrFailure("bootstrap");
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());

        assertThat(name).isEqualTo("bootstrap");
        assertThat(bootstrap.name()).isEqualTo("bootstrap");
        assertThat(provider.adoptProvisional(empty, secrets(empty))).isEqualTo(empty);

        OrionDocument configured = proxyDocument("user-proxy", "file:///upstream.git");
        provider.activate(() -> configured, secrets(configured));
        NativeGitRepository uiProxy = provider.provider().openForWrite("proxy/system/user-proxy").valueOrFailure("UI proxy");
        assertThat(uiProxy.name()).isEqualTo("proxy/system/user-proxy");
        assertThat(uiProxy).isNotSameAs(bootstrap);
        assertThat(provider.provider().openForRead("bootstrap").valueOrFailure("bootstrap")).isSameAs(bootstrap);

        provider.activate(() -> empty, secrets(empty));
        assertThat(provider.provider().openForWrite("bootstrap").valueOrFailure("bootstrap")).isSameAs(bootstrap);
        assertThatThrownBy(uiProxy::refs).isInstanceOf(IllegalStateException.class);
        bootstrap.files().withAccess("main", "save", GitCommitAuthor.EMPTY, access -> {
            access.write("orion.xml", new byte[]{1});
            access.apply();
            return null;
        });
        assertThat(pushes).hasValue(1);
    }

    @Test
    void publicAliasRefreshesAndPublishesUsingItsOwnAuthorizationIdentity() throws Exception {
        var refreshes = new AtomicInteger();
        var pushes = new AtomicInteger();
        var provider = provider(refreshes, pushes);
        var document = proxyDocument("configuration", "file:///upstream.git");
        String endpoint = "proxy/system/configuration";
        assertThat(provider.provider().isPublicRepositoryName(endpoint)).isFalse();
        provider.activate(() -> document, secrets(document));

        assertThat(provider.provider().isPublicRepositoryName(endpoint)).isTrue();
        assertThat(provider.provider().exists(endpoint)).isTrue();
        var repository = provider.provider().openForWrite(endpoint).valueOrFailure("public proxy");
        assertThat(repository.name()).isEqualTo(endpoint);
        assertThat(refreshes).hasValue(2);
        var update = FileTestSupport.prepared(repository.files(), "refs/heads/main", "public push",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("file", new byte[]{1});
            return null;
        });
        var authorizedNames = new ArrayList<String>();
        var statuses = provider.provider().publishPack(endpoint, update.pack(), update.refUpdates(), true,
                new GitNativeRepositoryAccessHook() {
                    @Override
                    public void beforeUpdate(String repositoryName, String refName, boolean force) {
                        authorizedNames.add(repositoryName);
                    }
                });
        assertThat(statuses).extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
        assertThat(authorizedNames).containsExactly(endpoint);
        assertThat(pushes).hasValue(1);
        assertThat(repository.files().readBytes("refs/heads/main", "file")).isEqualTo(new byte[]{1});
        assertThat(provider.provider().isPublicRepositoryName(
                BootstrapGitLocation.persistent(document.system().proxies().getFirst(), document.system()).proxyName())).isFalse();
    }

    @Test
    void removedOrReboundAliasCannotUseItsOldHandleOrCreateALocalRepository() throws Exception {
        var provider = provider(new AtomicInteger(), new AtomicInteger());
        String endpoint = "proxy/system/configuration";
        var first = proxyDocument("configuration", "file:///first.git");
        provider.activate(() -> first, secrets(first));
        var retained = provider.provider().openForWrite(endpoint).valueOrFailure("original proxy");
        var second = proxyDocument("configuration", "file:///second.git");
        provider.retry(new RemoteAlias("configuration"), () -> second, secrets(second));
        assertThatThrownBy(retained::refs).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retained.files().withAccess("refs/heads/main", "stale handle",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("file", new byte[]{1});
            fileAccess.apply();
            return null;
        })).isInstanceOf(IllegalStateException.class);
        var replacement = provider.provider().openForRead(endpoint).valueOrFailure("rebound proxy");
        var empty = OrionDocument.withAccessControl(new AccessControl());
        provider.activate(() -> empty, secrets(empty));
        assertThatThrownBy(replacement::refs).isInstanceOf(IllegalStateException.class);
        for (String name : List.of(endpoint, "proxy/system/missing", "proxy%2fsystem%2fmissing")) {
            assertThat(provider.provider().isPublicRepositoryName(name)).isFalse();
            assertThat(provider.provider().exists(name)).isFalse();
            assertThat(provider.provider().create(name)).isInstanceOf(Result.Failure.class);
            assertThat(provider.provider().openForRead(name)).isInstanceOf(Result.Failure.class);
        }
    }

    @Test
    void aliasCollisionLeavesTheActiveProxyAndLocalRepositoryIntact() throws Exception {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.inMemory();
        var provider = provider(backend);
        var first = proxyDocument("first", "file:///first.git");
        provider.activate(() -> first, secrets(first));
        var retained = provider.provider().openForRead("proxy/system/first").valueOrFailure("active proxy");
        NativeGitRepository local = provider.provider().openBacking("proxy/system/occupied", backend);
        local.files().withAccess("refs/heads/main", "local content", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("local", new byte[]{2});
            fileAccess.apply();
            return null;
        });
        var collision = proxyDocument("occupied", "file:///other.git");

        assertThatThrownBy(() -> provider.activate(() -> collision, secrets(collision)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> provider.retry(new RemoteAlias("occupied"), () -> collision, secrets(collision)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        assertThat(provider.provider().exists("proxy/system/first")).isTrue();
        assertThat(retained.refs()).isEmpty();
        assertThat(local.files().readBytes("refs/heads/main", "local")).isEqualTo(new byte[]{2});
    }

    @Test
    void retriesOnlyTheSelectedAliasAndRetainsFailedBindingsForRecovery() {
        var visited = new ArrayList<String>();
        var unavailable = new AtomicBoolean(true);
        var provider = new NativeGitRepositoryFactory(NativeGitRepositoryBackend.inMemory(),
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
                Optional.empty(), List.of(), List.of(initial.system().proxies().getFirst(), added),
                        initial.system().connections(),
                initial.system().oidcProviders()), List.of()));

        assertThat(provider.retry(added.alias(), current::get, secrets(current.get())))
                .isInstanceOfSatisfying(Result.Failure.class, failure ->
                        assertThat(failure.throwable()).isInstanceOf(BootstrapGitProxyException.class));
        assertThat(provider.syncObservation(added, current.get().system()).status()).isEqualTo(AUTHENTICATION_FAILED);
        unavailable.set(false);
        assertThat(provider.retry(added.alias(), current::get, secrets(current.get()))
                .valueOrFailure("retry").status()).isEqualTo(SUCCESS);
        assertThat(visited).containsExactly("/other.git", "/other.git");
        assertThat(provider.syncObservation(initial.system().proxies().getFirst(), initial.system()).status()).isEqualTo(SUCCESS);
        assertThat(provider.provider().repositoryNames()).isEmpty();
    }

    @Test
    void observesActiveBindingsWithoutRefreshingOrPublishingAndTracksConfigurationIdentity() {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger pushes = new AtomicInteger();
        var provider = provider(refreshes, pushes);
        OrionDocument document = proxyDocument("configuration", "file:///upstream.git");
        GitProxyBinding binding = document.system().proxies().getFirst();
        assertThat(provider.syncObservation(binding, document.system()).status()).isEqualTo(NOT_CHECKED);
        provider.activate(() -> document, secrets(document));
        refreshes.set(0);

        var observation = provider.syncObservation(binding, document.system());

        assertThat(observation.status()).isEqualTo(SUCCESS);
        assertThat(observation.observedAt()).isNotNull();
        assertThat(provider.syncObservation(binding, document.system())).isEqualTo(observation);
        assertThat(provider.syncObservation(
                proxyDocument("configuration", "file:///other.git").system().proxies().getFirst(), document.system()).status())
                .isEqualTo(NOT_CHECKED);
        assertThat(refreshes).hasValue(0);
        assertThat(pushes).hasValue(0);
    }

    @Test
    void recordsSafeNativeAuthenticationFailureThenRecovery() {
        var failure = new AtomicReference<GitClientFailure>();
        var provider = new NativeGitRepositoryFactory(
                NativeGitRepositoryBackend.inMemory(), new BootstrapSecretResolver(Map.of()),
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
        String internal = BootstrapGitLocation.persistent(binding, document.system()).proxyName();

        assertThatThrownBy(() -> provider.provider().openForRead(internal))
                .hasMessageNotContaining("secret")
                .hasCauseInstanceOf(GitClientTransportException.class)
                .cause().hasMessage("private upstream response with secret");
        assertThat(provider.syncObservation(binding, document.system()).status()).isEqualTo(AUTHENTICATION_FAILED);
        var failedAt = provider.syncObservation(binding, document.system()).observedAt();
        assertThat(failedAt).isNotNull();

        failure.set(new GitClientFailure(GitClientFailure.Kind.TIMEOUT,
                GitClientFailure.Phase.OPEN, true, "private timeout details", null));
        assertThatThrownBy(() -> provider.provider().openForRead(internal)).hasMessageNotContaining("private");
        assertThat(provider.syncObservation(binding, document.system()).status()).isEqualTo(UNAVAILABLE);

        failure.set(null);
        provider.provider().openForRead(internal).valueOrFailure("recovered");
        assertThat(provider.syncObservation(binding, document.system()).status()).isEqualTo(SUCCESS);
        assertThat(provider.syncObservation(binding, document.system()).observedAt()).isAfterOrEqualTo(failedAt);
    }

    @Test
    void passesPreparedPackUnchangedToTheUpstreamPusher() throws Exception {
        ArrayList<byte[]> forwarded = new ArrayList<>();
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(
                NativeGitRepositoryBackend.inMemory(), new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) -> {
                    ByteArrayOutputStream exported = new ByteArrayOutputStream();
                    {
                        GitIndexAccess access1 = repository.index().createAccess();
                        try {
                            repository.writePack(access1.packs(received.orElseThrow()).getFirst(),
                                    new OutputStreamBufferedByteOutput(exported));
                            forwarded.add(exported.toByteArray());
                            return Collections.nCopies(updates.size(), true);
                        } finally {
                            access1.discard();
                        }
                    }
                });
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.provider().openForWrite(name).valueOrFailure("repository");
        var prepared = FileTestSupport.prepared(repository.files(), "main", "prepared", GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.write("orion.xml", new byte[]{1});
            return null;
        });

        GitOperationException.requireSuccess(provider.provider().publishPack(
                name, prepared.pack(), prepared.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL));

        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.getFirst()).isEqualTo(prepared.pack());
    }

    @Test
    void checksRefAccessBeforeForwardingAnInternalPack() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), pushes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.provider().openForWrite(name).valueOrFailure("repository");
        var prepared = FileTestSupport.prepared(repository.files(), "refs/heads/main", "update",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "updated".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        Map<String, String> initialRefs = repository.refs();
        GitNativeRepositoryAccessHook denied = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeUpdate(String repositoryName, String refName, boolean force) {
                assertThat(repositoryName).isEqualTo(name);
                throw new AccessDeniedException("denied", null);
            }
        };

        assertThat(provider.provider().publishPack(name, prepared.pack(), prepared.refUpdates(), true, denied))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.REJECTED);
        assertThat(pushes).hasValue(0);
        assertThat(repository.refs()).isEqualTo(initialRefs);

        GitOperationException.requireSuccess(provider.provider().publishPack(
                name, prepared.pack(), prepared.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL));
        assertThat(pushes).hasValue(1);
    }

    @Test
    void keepsActiveBootstrapCacheInternalWhileOrdinaryNamesRemainPublic() {
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), new AtomicInteger());
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));

        assertThat(provider.provider().isPublicRepositoryName(name)).isFalse();
        assertThat(provider.provider().isPublicRepositoryName(name.replace("/", "%2F"))).isFalse();
        assertThat(provider.provider().openForRead(name)).isInstanceOf(Result.Success.class);
        assertThat(provider.provider().isPublicRepositoryName("team/repo")).isTrue();
        activateAdopted(provider);
        assertThat(provider.provider().isPublicRepositoryName(name)).isFalse();
    }

    @Test
    void canonicalizesBeforeProxyBindingLookupAndBackendAccess() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(refreshes, publishes);
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        activateAdopted(provider);
        refreshes.set(0);

        NativeGitRepository repository = provider.provider().openForRead(name.replace("/", "%2F"))
                .valueOrFailure("proxy repository");
        assertThat(refreshes).hasValue(1);
        NativeGitRepository writable = provider.provider().openForWrite(name.replace("/", "%5C"))
                .valueOrFailure("repository");
        assertThat(refreshes).hasValue(2);
        writable.files()
                .withAccess("refs/heads/main", "save through proxy", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "configuration".getBytes(StandardCharsets.UTF_8));
            assertThat(refreshes).hasValue(2);
            assertThat(publishes).hasValue(0);
            fileAccess.apply();
            return null;
        });

        assertThat(repository.name()).isEqualTo(name);
        assertThat(writable.name()).isEqualTo(name);
        assertThat(refreshes).hasValue(3);
        assertThat(publishes).hasValue(1);
    }

    @Test
    void canonicalizesLocalBootstrapRepositoryIdentity() {
        NativeGitRepositoryFactory provider = provider(
                new AtomicInteger(),
                new AtomicInteger());

        Optional<String> repositoryName = provider.resolveProvisional(
                "configuration",
                localSource("team%2Frepo"),
                true);

        assertThat(repositoryName).contains("team/repo");
        assertThat(provider.provider().exists("team/repo")).isTrue();
    }

    @Test
    void rejectsInvalidLocalBootstrapRepositoryNames() {
        NativeGitRepositoryFactory provider = provider(
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
        NativeGitRepositoryFactory provider = provider(
                refreshes,
                new AtomicInteger());

        String repositoryName = provider.prepareProvisional(
                "configuration",
                remoteSource("orion.xml"));
        provider.provider().openForRead(repositoryName).valueOrFailure("open proxy");
        provider.provider().openForRead(repositoryName).valueOrFailure("open proxy");

        assertThat(refreshes).hasValue(3);
    }

    @Test
    void reusesCanonicalProxyAliasOnlyForMatchingAuthentication() {
        AtomicInteger refreshes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(
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
        NativeGitRepositoryFactory provider = provider(
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

        BootstrapSourceConfig material = remoteHttpSource(
                "git+https://example.test/material.git", "material.p12", "env:MATERIAL_TOKEN");
        assertThatThrownBy(() -> provider.prepareProvisional("configuration", material))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source binding conflicts");
        assertThat(provider.prepareProvisional("configuration", configuration)).isEqualTo(repositoryName);
        assertThat(provider.prepareProvisional("material", material))
                .isEqualTo(BootstrapGitLocation.parse(material).proxyName());
    }

    @Test
    void routesProxyFileSavesThroughUpstreamCompareAndSet() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger pushes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(
                refreshes,
                pushes);
        String repositoryName = provider.prepareProvisional(
                "configuration",
                remoteSource("orion.xml"));

        assertThat(refreshes).hasValue(1);
        NativeGitRepository writable = provider.provider().openForWrite(repositoryName).valueOrFailure("repository");
        assertThat(refreshes).hasValue(2);
        writable.files().withAccess("refs/heads/main",
                "initialize configuration", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "configuration".getBytes(StandardCharsets.UTF_8));
            assertThat(refreshes).hasValue(2);
            assertThat(pushes).hasValue(0);
            fileAccess.apply();
            return null;
        });
        assertThat(refreshes).hasValue(3);
        assertThat(pushes).hasValue(1);

        NativeGitRepository repository = provider.provider().openForRead(repositoryName)
                .valueOrFailure("open local proxy");
        assertThat(pushes).hasValue(1);
        assertThat(refreshes).hasValue(4);
        assertThat(repository.files().readBytes("refs/heads/main", "orion.xml"))
                .isEqualTo("configuration".getBytes(StandardCharsets.UTF_8));
        provider.provider().openForWrite(repositoryName).valueOrFailure("repository").files().withAccess("refs/heads/main",
                "delete configuration", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.delete("orion.xml");
            fileAccess.apply();
            return null;
        });
        assertThat(pushes).hasValue(2);
        assertThatThrownBy(() -> repository.files().readBytes("refs/heads/main", "orion.xml"))
                .isInstanceOf(GitOperationException.class);
    }

    @Test
    void oneReadHandleRefreshesOnceAcrossMultipleRepositoryReads() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(refreshes, new AtomicInteger());
        String repositoryName = provider.prepareProvisional("configuration", remoteSource("orion.xml"));

        NativeGitRepository repository = provider.provider().openForRead(repositoryName).valueOrFailure("open proxy");
        repository.refs();
        repository.readObject(new ObjectId("0".repeat(40)));
        repository.index().withAccess(access2 -> {
            {
                GitStorageAccess storageAccess = repository.storage().createAccess();
                try {
                    GitObjectRead.exists(storageAccess, access2, new ObjectId("0".repeat(40)));

                    assertThat(refreshes).hasValue(2);
                    return null;
                } finally {
                    storageAccess.discard();
                }
            }
        });
    }

    @Test
    void proxyHandleRejectsDirectObjectMutation() {
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), new AtomicInteger());
        String repositoryName = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.provider().openForWrite(repositoryName).valueOrFailure("open proxy");

        assertThatThrownBy(() -> repository.writeObject(GitObjectType.BLOB, new byte[]{1}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void retainedProxyHandleRoutesSavesThroughProviderPublication() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), pushes);
        String repositoryName = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepository repository = provider.provider().find(repositoryName).valueOrFailure("find proxy alias");

        repository.files().withAccess("refs/heads/main", "update configuration", GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.write("orion.xml", "updated".getBytes(StandardCharsets.UTF_8));
            fileAccess.apply();
            return null;
        });

        assertThat(pushes).hasValue(1);
        repository.files().withAccess("refs/heads/main", "delete configuration", GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.delete("orion.xml");
            fileAccess.apply();
            return null;
        });
        assertThat(pushes).hasValue(2);
        assertThatThrownBy(() -> repository.files().readBytes("refs/heads/main", "orion.xml"))
                .isInstanceOf(GitOperationException.class);
    }

    @Test
    void keepsOrdinaryLocalRepositoriesDirect() throws Exception {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.inMemory();
        AtomicInteger refreshes = new AtomicInteger();
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(backend, new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> refreshes.incrementAndGet(), (location, transport,
                repository, received, updates, atomic) ->
                        Collections.nCopies(updates.size(), true));
        provider.provider().openBacking("local", backend);

        provider.provider().openForWrite("local").valueOrFailure("repository").files().withAccess("refs/heads/main",
                "direct", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("file.txt", new byte[]{1});
            fileAccess.apply();
            return null;
        });

        assertThat(refreshes).hasValue(0);
        assertThat(provider.provider().openForRead("local")).isNotNull();
    }

    @Test
    void activeProviderCannotReenterExternalBootstrapResolution() {
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), new AtomicInteger());
        provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        activateAdopted(provider);

        assertThatThrownBy(() -> provider.prepareProvisional("configuration", remoteSource("orion.xml")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
        assertThatThrownBy(() -> provider.resolveProvisional("local", localSource("ordinary"), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provisional phase");
    }

    @Test
    void activationAtomicallyReplacesPersistentBindingCatalog() {
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), new AtomicInteger());
        OrionDocument first = proxyDocument("first", "file:///first.git");
        OrionDocument second = proxyDocument("second", "file:///second.git");
        String firstName = BootstrapGitLocation.persistent(first.system().proxies().getFirst(), first.system()).proxyName();
        String secondName = BootstrapGitLocation.persistent(second.system().proxies().getFirst(), second.system()).proxyName();

        provider.activate(() -> first, secrets(first));
        assertThat(provider.provider().openForRead(firstName)).isInstanceOf(Result.Success.class);
        provider.activate(() -> second, secrets(second));

        assertUnavailableCache(provider, firstName);
        assertThat(provider.provider().openForRead(secondName)).isInstanceOf(Result.Success.class);
    }

    @Test
    void activationRejectsAnUnadoptedSourceAmongMultipleBindings() {
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), new AtomicInteger());
        String first = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        BootstrapSourceConfig material = remoteSource("material.p12");
        material.setLocation("git+file:///material.git");
        String second = provider.prepareProvisional("material", material);
        OrionDocument incomplete = proxyDocument("configuration", "file:///upstream.git");

        assertThatThrownBy(() -> provider.activate(() -> incomplete, secrets(incomplete)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("adopted");
        assertThat(provider.provider().openForRead(first)).isInstanceOf(Result.Success.class);
        assertThat(provider.provider().openForRead(second)).isInstanceOf(Result.Success.class);
        assertThat(provider.provider().repositoryNames()).isEmpty();
    }

    @Test
    void retryPreservesADeferredInternalBootstrapSource() {
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), new AtomicInteger());
        String source = provider.prepareProvisional("configuration", remoteSource("orion.xml"));
        OrionDocument document = proxyDocument("unrelated", "file:///other.git");

        provider.activate(() -> document, secrets(document), true);
        assertThat(provider.provider().openForRead(source)).isInstanceOf(Result.Success.class);

        provider.retry(new RemoteAlias("unrelated"), () -> document, secrets(document));

        assertThat(provider.provider().openForRead(source)).isInstanceOf(Result.Success.class);
        provider.activate(() -> document, secrets(document), true);
        assertThat(provider.provider().openForRead(source)).isInstanceOf(Result.Success.class);
        assertThat(provider.provider().repositoryNames()).doesNotContain(source);
        assertThat(provider.provider().isPublicRepositoryName(source)).isFalse();
    }

    @Test
    void removingAnAdoptedBindingMakesItsCacheUnavailable() {
        AtomicInteger refreshes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(refreshes, new AtomicInteger());
        String repositoryName = provider.prepareProvisional("material", remoteSource("orion.xml"));

        activateAdopted(provider);
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        provider.activate(() -> empty, secrets(empty));
        assertUnavailableCache(provider, repositoryName);

        assertThat(refreshes).hasValue(2);
    }

    @Test
    void retainedHandleCannotReadOrPublishAfterItsBootstrapBindingIsRemoved() {
        AtomicInteger pushes = new AtomicInteger();
        NativeGitRepositoryFactory provider = provider(new AtomicInteger(), pushes);
        String name = provider.prepareProvisional("material", remoteSource("orion.xml"));
        NativeGitRepository retained = provider.provider().openForWrite(name).valueOrFailure("proxy handle");
        activateAdopted(provider);
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        provider.activate(() -> empty, secrets(empty));

        assertThatThrownBy(retained::refs).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retained.files().withAccess("refs/heads/main", "save", GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.write("orion.xml", new byte[]{1});
            fileAccess.apply();
            return null;
        }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retained.publishRefs(List.of(), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(pushes).hasValue(0);
    }

    @Test
    void hidesCacheAfterProviderRestartAndAllowsBootstrapToRebindIt(@TempDir Path root) {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.file(root);
        NativeGitRepositoryFactory original = provider(backend);
        String name = original.prepareProvisional("configuration", remoteSource("orion.xml"));
        NativeGitRepositoryFactory restarted = provider(NativeGitRepositoryBackend.file(root));

        assertUnavailableCache(restarted, name);
        assertThat(restarted.prepareProvisional("configuration", remoteSource("orion.xml"))).isEqualTo(name);
        assertThat(restarted.provider().openForRead(name)).isInstanceOf(Result.Success.class);
        assertThat(restarted.provider().repositoryNames()).doesNotContain(name);
    }

    @Test
    void failedBootstrapRefreshLeavesNoAccessibleCacheAndAllowsNewAuthentication() {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.inMemory();
        AtomicInteger refreshes = new AtomicInteger();
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(
                backend, new BootstrapSecretResolver(Map.of("OLD_TOKEN", "old", "NEW_TOKEN", "new")),
                (location, transport, repository) -> {
                    if (refreshes.incrementAndGet() == 1) {
                        throw new IllegalStateException("upstream unavailable");
                    }
                },
                (location, transport, repository, received, updates, atomic) -> List.of());
        BootstrapSourceConfig source = remoteHttpSource(
                "git+https://example.test/orion.git", "orion.xml", "env:OLD_TOKEN");

        assertThatThrownBy(() -> provider.prepareProvisional("configuration", source))
                .isInstanceOf(BootstrapGitProxyException.class);
        String name = "bootstrap";
        assertThat(provider.provider().backingExists(name)).isTrue();
        assertUnavailableCache(provider, name);

        BootstrapSourceConfig replacement = remoteHttpSource(
                "git+https://example.test/orion.git", "orion.xml", "env:NEW_TOKEN");
        assertThat(provider.prepareProvisional("configuration", replacement)).isEqualTo(name);
        assertThat(provider.provider().openForRead(name)).isInstanceOf(Result.Success.class);
        assertThatThrownBy(() -> provider.prepareProvisional("material", source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap proxy binding configuration conflicts");
    }

    @Test
    void failedSharedRefreshPreservesOriginalBindingAndReleasesTheNewSource() {
        AtomicInteger refreshes = new AtomicInteger();
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(
                NativeGitRepositoryBackend.inMemory(), new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> {
                    if (refreshes.incrementAndGet() == 2) {
                        throw new IllegalStateException("upstream unavailable");
                    }
                },
                (location, transport, repository, received, updates, atomic) -> List.of());
        String name = provider.prepareProvisional("configuration", remoteSource("orion.xml"));

        assertThatThrownBy(() -> provider.prepareProvisional("aaa-material", remoteSource("material.p12")))
                .isInstanceOf(BootstrapGitProxyException.class);

        assertThat(provider.provider().exists(name)).isTrue();
        assertThat(provider.provider().openForRead(name)).isInstanceOf(Result.Success.class);
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        OrionDocument adopted = provider.adoptProvisional(empty, secrets(empty));
        assertThat(adopted.system().proxies()).isEmpty();
        BootstrapSourceConfig replacement = remoteSource("material.p12");
        replacement.setLocation("git+file:///material.git");
        assertThat(provider.prepareProvisional("aaa-material", replacement))
                .isEqualTo(BootstrapGitLocation.parse(replacement).proxyName());
    }

    @Test
    void missingRequiredRefLeavesNoProvisionalBinding() {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.inMemory();
        NativeGitRepositoryFactory provider = provider(backend);
        BootstrapSourceConfig source = remoteSource("orion.xml");

        assertThatThrownBy(() -> provider.resolveProvisional("configuration", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source ref is unavailable: configuration");

        assertUnavailableCache(provider, "bootstrap");
        BootstrapSourceConfig replacement = remoteSource("orion.xml");
        replacement.setLocation("git+file:///replacement.git");
        assertThatThrownBy(() -> provider.resolveProvisional("configuration", replacement, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source ref is unavailable: configuration");
        assertThat(provider.resolveProvisional("configuration", source, true)).contains("bootstrap");
        assertThat(provider.provider().openForRead("bootstrap").valueOrFailure("bootstrap").refs()
                .get("refs/heads/main")).isNull();
    }

    @Test
    void missingRequiredPathLeavesNoProvisionalBinding() throws Exception {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.inMemory();
        BootstrapSourceConfig source = remoteSource("orion.xml");
        String name = "bootstrap";
        NativeGitRepositoryFactory provider = provider(backend);
        NativeGitRepository repository = provider.provider().openBacking(name, backend);
        repository.files().withAccess("main", "seed", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("other.xml", new byte[]{1});
            fileAccess.apply();
            return null;
        });
        assertThatThrownBy(() -> provider.resolveProvisional("configuration", source, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: configuration");

        assertUnavailableCache(provider, name);
        BootstrapSourceConfig replacement = remoteSource("orion.xml");
        replacement.setLocation("git+file:///replacement.git");
        assertThatThrownBy(() -> provider.resolveProvisional("configuration", replacement, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: configuration");
        repository.files().withAccess("main", "repair", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", new byte[]{2});
            fileAccess.apply();
            return null;
        });
        provider.resolveProvisional("configuration", source, false);
        assertThat(repository.refs()).containsKey("refs/heads/main");
    }

    @Test
    void failedSharedSourcePreservesPreviouslyResolvedBinding() throws Exception {
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.inMemory();
        BootstrapSourceConfig configuration = remoteSource("orion.xml");
        String name = "bootstrap";
        NativeGitRepositoryFactory provider = provider(backend);
        provider.provider().openBacking(name, backend).files().withAccess("main", "seed",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", new byte[]{1});
            fileAccess.apply();
            return null;
        });
        Optional<String> resolved = provider.resolveProvisional("configuration", configuration, false);
        NativeGitRepository retained = provider.provider().openForRead(name).valueOrFailure("proxy handle");

        assertThatThrownBy(() -> provider.resolveProvisional("material", remoteSource("material.p12"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: material");

        BootstrapSourceConfig material = remoteSource("material.p12");
        material.setLocation("git+file:///material.git");
        assertThat(provider.resolveProvisional("material", material, true))
                .hasValue(BootstrapGitLocation.parse(material).proxyName());
        assertThatThrownBy(() -> provider.resolveProvisional(
                "configuration", remoteSource("missing.xml"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source path is unavailable: configuration");
        assertThat(retained.refs().get("refs/heads/main")).isNotNull();
        assertThat(provider.resolveProvisional("configuration", configuration, false)).isEqualTo(resolved);
    }

    private static void assertUnavailableCache(NativeGitRepositoryFactory provider, String name) {
        for (String spelling : List.of(name, name.replace("/", "%2F"))) {
            assertThat(provider.provider().exists(spelling)).isFalse();
            assertThat(provider.provider().find(spelling)).isInstanceOf(Result.Failure.class);
            assertThat(provider.provider().openForRead(spelling)).isInstanceOf(Result.Failure.class);
            assertThat(provider.provider().openForWrite(spelling)).isInstanceOf(Result.Failure.class);
            assertThat(provider.provider().create(spelling)).isEqualTo(new Result.Failure<>(
                    Result.FailureCode.NOT_SUPPORTED, "Bootstrap cache is internal"));
            assertThatThrownBy(() -> provider.provider().openForWrite(spelling).valueOrFailure("repository").files()
                    .withAccess("refs/heads/main", "save", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("orion.xml", new byte[]{1});
                fileAccess.apply();
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> provider.resolveProvisional("local", localSource(spelling), true))
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
        assertThat(provider.provider().repositoryNames()).doesNotContain(name);
    }

    private static NativeGitRepositoryFactory provider(NativeGitRepositoryBackend backend) {
        return new NativeGitRepositoryFactory(
                backend, new BootstrapSecretResolver(Map.of()),
                (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) ->
                        Collections.nCopies(updates.size(), true));
    }

    private static NativeGitRepositoryFactory provider(
            AtomicInteger refreshes,
            AtomicInteger pushes) {
        return provider(refreshes, pushes, Map.of());
    }

    private static NativeGitRepositoryFactory provider(
            AtomicInteger refreshes,
            AtomicInteger pushes,
            Map<String, String> environment) {
        return new NativeGitRepositoryFactory(NativeGitRepositoryBackend.inMemory(), new BootstrapSecretResolver(environment), (location,
                transport, repository) -> refreshes.incrementAndGet(), (location, transport, repository,
                received, updates, atomic) -> {
                    pushes.incrementAndGet();
                    return Collections.nCopies(updates.size(), true);
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
                "credentialKind", "token",
                "credential", credentialReference));
        return source;
    }

    private static void activateAdopted(NativeGitRepositoryFactory provider) {
        OrionDocument empty = OrionDocument.withAccessControl(new AccessControl());
        ConfigurationSecrets secrets = secrets(empty);
        OrionDocument adopted = provider.adoptProvisional(empty, secrets);
        provider.activate(() -> adopted, secrets);
    }

    private static OrionDocument proxyDocument(String alias, String upstream) {
        var binding = new GitProxyBinding(new RemoteAlias(alias),
                new GitProxyBinding.Direct(URI.create(upstream), GitCredentialKind.NONE, Optional.empty(), Optional.empty()), "main");
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(),
                List.of(), List.of(binding), List.of(),
                List.of()), List.of());
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
