package pro.deta.orion.git.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.util.Result;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistentProxyActivationTest {
    @Test
    void retainedHandlesUseStoredCredentialsAndPickUpRotationOnTheNextConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            var retained = provider.openForWrite(name).valueOrFailure("proxy");
            fixture.adopt(provider);
            fixture.rotate("stored-token");

            provider.activate(fixture.current::get, fixture.secrets);
            fixture.authorization.clear();
            retained.saveFiles("main", Map.of("orion.xml", GitFile.regular(new byte[]{1})), Set.of(), "save",
                    GitCommitAuthor.EMPTY);
            assertThat(fixture.authorization).containsExactly("Bearer stored-token", "Bearer stored-token");

            fixture.rotate("rotated-token");
            provider.openForRead(name.replace("/", "%2F")).valueOrFailure("same cache");
            assertThat(fixture.authorization.getLast()).isEqualTo("Bearer rotated-token");
            assertThat(retained.name()).isEqualTo(name);
            assertThat(provider.repositoryNames()).doesNotContain(name);
            assertThat(provider.isPublicRepositoryName(name)).isFalse();
        }
    }

    @Test
    void reloadUsesCredentialMetadataAndValueFromOneSnapshot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            fixture.adopt(provider);
            provider.activate(fixture.current::get, fixture.secrets);
            OrionDocument changed = fixture.secrets.createSystem(fixture.current.get(), "basic", "password".toCharArray());
            GitProxyBinding previous = changed.system().proxies().getFirst();
            GitProxyBinding basic = new GitProxyBinding(previous.alias(), previous.upstream(), previous.ref(),
                    GitCredentialKind.PASSWORD, Optional.of("basic"), Optional.of("user"),
                    Optional.empty());
            fixture.current.set(withProxies(changed, List.of(basic)));

            provider.openForRead(name).valueOrFailure("reloaded proxy");

            assertThat(fixture.authorization.getLast()).isEqualTo("Basic dXNlcjpwYXNzd29yZA==");
        }
    }

    @Test
    void reassigningAnAliasCannotRedirectAnAlreadyResolvedSource() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            fixture.adopt(provider);
            provider.activate(fixture.current::get, fixture.secrets);
            GitProxyBinding previous = fixture.current.get().system().proxies().getFirst();
            GitProxyBinding redirected = new GitProxyBinding(previous.alias(),
                    URI.create(previous.upstream() + "/other"), previous.ref(), previous.credentialKind(),
                    previous.secret(), previous.username(), previous.knownHosts());
            fixture.current.set(withProxies(fixture.current.get(), List.of(redirected)));
            fixture.authorization.clear();

            assertThatThrownBy(() -> provider.openForRead(name))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("persistent binding lookup");
            assertThat(fixture.authorization).isEmpty();
        }
    }

    @Test
    void failedCandidateRefreshDoesNotExposeAnyNewCacheOrSwitchExistingCredentials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            fixture.adopt(provider);
            fixture.rotate("stored-token");
            GitProxyBinding first = fixture.current.get().system().proxies().getFirst();
            GitProxyBinding unavailable = new GitProxyBinding(new RemoteAlias("second"),
                    URI.create(first.upstream() + "/unavailable"), first.ref(), first.credentialKind(),
                    first.secret(), first.username(), first.knownHosts());
            fixture.current.set(withProxies(fixture.current.get(), List.of(first, unavailable)));
            fixture.unavailable.set(unavailable.upstream());

            assertThatThrownBy(() -> provider.activate(fixture.current::get, fixture.secrets))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("upstream synchronization");

            provider.openForRead(name).valueOrFailure("provisional source after failed activation");
            assertThat(fixture.authorization.getLast()).isEqualTo("Bearer external-token");
            String failedCache = BootstrapGitLocation.persistent(unavailable).proxyName();
            assertThat(provider.openForRead(failedCache)).isInstanceOf(Result.Failure.class);
            assertThat(provider.isPublicRepositoryName(failedCache)).isFalse();
            assertThat(provider.repositoryNames()).isEmpty();
        }
    }

    @Test
    void rejectsMissingAdoptionAndLeavesProvisionalSourceUsable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            assertThatThrownBy(() -> provider.activate(fixture.current::get, fixture.secrets))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("adopted");
            assertThat(provider.openForRead(name)).isInstanceOf(Result.Success.class);
            assertThat(fixture.authorization.getLast()).isEqualTo("Bearer external-token");
            assertThat(provider.isPublicRepositoryName(name)).isFalse();
        }
    }

    @Test
    void invalidCredentialPreventsTheWholeActivationAndDoesNotFallBackAfterRotation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            fixture.adopt(provider);
            OrionDocument valid = fixture.current.get();
            var invalid = new ConfigurationSecret("configuration-credential", "invalid-envelope");
            fixture.current.set(new OrionDocument(new OrionDocument.SystemConfiguration(
                    valid.system().accessControl(), valid.system().https(), List.of(invalid),
                    valid.system().proxies()), valid.organizations()));

            assertThatThrownBy(() -> provider.activate(fixture.current::get, fixture.secrets))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-envelope");
            provider.openForRead(name).valueOrFailure("provisional source");
            assertThat(fixture.authorization.getLast()).isEqualTo("Bearer external-token");
            OrionDocument corrupt = fixture.current.get();
            fixture.current.set(valid);
            provider.activate(fixture.current::get, fixture.secrets);
            fixture.current.set(corrupt);
            fixture.authorization.clear();

            assertThatThrownBy(() -> provider.openForRead(name))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-envelope");
            assertThat(fixture.authorization).isEmpty();
        }
    }

    @Test
    void failedActivationKeepsThePreviouslyActiveBindingSet() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            fixture.adopt(provider);
            provider.activate(fixture.current::get, fixture.secrets);
            OrionDocument invalid = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.empty(), List.of(new ConfigurationSecret("broken", "invalid-envelope")), List.of()),
                    List.of());

            assertThatThrownBy(() -> provider.activate(() -> invalid, fixture.secrets))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(provider.openForRead(name)).isInstanceOf(Result.Success.class);
        }
    }

    @Test
    void removingAnActiveBindingRevokesRetainedHandlesAndKeepsItsCachePrivate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var provider = fixture.provider();
            String name = provider.prepareProvisional("configuration", fixture.source());
            var retained = provider.openForWrite(name).valueOrFailure("proxy");
            fixture.adopt(provider);
            provider.activate(fixture.current::get, fixture.secrets);
            fixture.current.set(withProxies(fixture.current.get(), List.of()));
            provider.activate(fixture.current::get, fixture.secrets);

            assertThat(provider.openForRead(name)).isInstanceOf(Result.Failure.class);
            assertThatThrownBy(retained::refs).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> retained.saveFiles("main", Map.of("file", GitFile.regular(new byte[]{1})), Set.of(),
                    "save", GitCommitAuthor.EMPTY)).isInstanceOf(IllegalStateException.class);
            assertThat(provider.repositoryNames()).doesNotContain(name);
            assertThat(provider.isPublicRepositoryName(name)).isFalse();
        }
    }

    private static OrionDocument withProxies(OrionDocument document, List<GitProxyBinding> bindings) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                document.system().https(), document.system().secrets(), bindings), document.organizations());
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final OrionKeyMaterial material;
        final List<String> authorization = new CopyOnWriteArrayList<>();
        final AtomicReference<URI> unavailable = new AtomicReference<>();
        final AtomicReference<OrionDocument> current = new AtomicReference<>(
                OrionDocument.withAccessControl(new AccessControl()));
        final ConfigurationSecrets secrets;

        Fixture() throws Exception {
            var signing = new KeyMaterialDescriptor(new KeyMaterialAlias("signing"),
                    KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1),
                    KeyMaterialScope.cluster("test"));
            try (var options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
                material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options,
                        new SigningMaterialSet(signing, List.of()), 2048, true);
            }
            secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/repository.git", exchange -> {
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            server.start();
        }

        BootstrapSourceConfig source() {
            var source = new BootstrapSourceConfig();
            source.setLocation("git+http://127.0.0.1:" + server.getAddress().getPort() + "/repository.git");
            source.setRef("main");
            source.setPath("orion.xml");
            source.setAuth(Map.of("credentialKind", "token", "credential", "env:TOKEN"));
            return source;
        }

        ProxyAwareNativeGitRepositoryProvider provider() {
            return new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider(),
                    new BootstrapSecretResolver(Map.of("TOKEN", "external-token")),
                    (location, transport, repository) -> {
                        if (location.remoteUri().equals(unavailable.get())) {
                            throw new IllegalStateException("Upstream unavailable");
                        }
                        new GitUploadPackClient(transport).discover(location.remoteUri(), GitClientOptions.defaults());
                    },
                    (location, transport, repository, received, updates, atomic) -> {
                        new GitUploadPackClient(transport).discover(location.remoteUri(), GitClientOptions.defaults());
                        return java.util.Collections.nCopies(updates.size(), true);
                    });
        }

        void adopt(ProxyAwareNativeGitRepositoryProvider provider) {
            current.set(provider.adoptProvisional(current.get(), secrets));
        }

        void rotate(String token) {
            current.set(secrets.replaceSystem(current.get(), "configuration-credential", token.toCharArray()));
        }

        @Override
        public void close() {
            server.stop(0);
            material.close();
        }
    }
}
