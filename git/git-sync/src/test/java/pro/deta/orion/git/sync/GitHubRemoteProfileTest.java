package pro.deta.orion.git.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientService;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportException;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitHttpRequestConfigurer;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialCapabilities;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RemoteRefMapping;
import pro.deta.orion.schema.orion.RemoteRole;
import pro.deta.orion.schema.orion.RemoteTrigger;
import pro.deta.orion.schema.orion.RemoteUpdatePolicy;
import pro.deta.orion.schema.orion.RepositoryRemote;
import pro.deta.orion.schema.orion.RepositoryAddress;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.TeamId;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRemoteProfileTest {
    private static final RepositoryAddress REPOSITORY = RepositoryAddress.parse("acme/platform/api");
    private final AtomicReference<OrionDocument> current = new AtomicReference<>();
    private final AtomicInteger resolutions = new AtomicInteger();
    private KeyMaterialService material;
    private ConfigurationSecrets secrets;

    @BeforeEach
    void configureStoredCredential() throws Exception {
        material = KeyMaterialService.open(new InMemoryKeyMaterialContentStore(),
                KeyMaterialOptions.pkcs12("test-password".toCharArray()));
        KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES,
                new KeyMaterialVersion(1), KeyMaterialScope.cluster("test"));
        material.generateSecretKeyIfMissing(descriptor, 256);
        secrets = new ConfigurationSecrets(() -> {
            resolutions.incrementAndGet();
            return current.get();
        }, KeyMaterialCapabilities.open(material, List.of(descriptor)).configurationCipher(descriptor));
        OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("api"), null,
                "refs/heads/main", RepositoryPolicy.safeDefaults(), List.of(), List.of(), List.of(), List.of());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(new OrionDocument.Organization(new OrganizationId("acme"), null, List.of(), List.of(),
                List.of(), List.of(new OrionDocument.Team(new TeamId("platform"), null, List.of(), List.of(),
                List.of(repository))), List.of())));
        current.set(secrets.create(document, ConfigurationScope.repository(REPOSITORY),
                "github-token", "fine-grained-token".toCharArray()));
    }

    @AfterEach
    void closeMaterial() {
        material.close();
    }

    @Test
    void configuresGitHubHttpsWithAUsernameAndToken() {
        AtomicReference<GitHttpRequestConfigurer> captured = new AtomicReference<>();
        GitHubRemoteProfile profile = new GitHubRemoteProfile(
                REPOSITORY,
                secrets,
                configurer -> {
                    captured.set(configurer);
                    return new UnusedTransport();
                });

        try (GitRemoteConnection connection = profile.open(remote(
                RemoteProvider.GITHUB,
                "https://github.com/acme/project.git"))) {
            assertThat(connection.uri())
                    .isEqualTo(URI.create("https://github.com/acme/project.git"));
            assertThat(resolutions).hasValue(1);
            HttpRequest.Builder request = HttpRequest.newBuilder(connection.uri());
            captured.get().configure(request);

            String authorization = request.build().headers()
                    .firstValue("Authorization")
                    .orElseThrow();
            assertThat(authorization).startsWith("Basic ");
            assertThat(decodeBasic(authorization))
                    .isEqualTo("x-access-token:fine-grained-token");
        }

        assertThatThrownBy(() -> captured.get().configure(
                HttpRequest.newBuilder(URI.create("https://github.com"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("fine-grained-token");
    }

    @Test
    void rejectsUnsupportedProviderAndHostBeforeResolvingCredentials() {
        GitHubRemoteProfile profile = new GitHubRemoteProfile(
                REPOSITORY,
                secrets,
                configurer -> new UnusedTransport());

        assertThatThrownBy(() -> profile.open(remote(
                RemoteProvider.GENERIC,
                "https://github.com/acme/project.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-token");
        assertThatThrownBy(() -> profile.open(remote(
                RemoteProvider.GITHUB,
                "https://example.com/acme/project.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-token");
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void usesRotatedCredentialsForTheNextConnection() {
        AtomicReference<GitHttpRequestConfigurer> captured = new AtomicReference<>();
        GitHubRemoteProfile profile = new GitHubRemoteProfile(REPOSITORY, secrets, configurer -> {
            captured.set(configurer);
            return new UnusedTransport();
        });
        RepositoryRemote remote = remote(RemoteProvider.GITHUB, "https://github.com/acme/project.git");
        try (GitRemoteConnection first = profile.open(remote)) {
            HttpRequest.Builder request = HttpRequest.newBuilder(first.uri());
            captured.get().configure(request);
            assertThat(decodeBasic(request.build().headers().firstValue("Authorization").orElseThrow()))
                    .isEqualTo("x-access-token:fine-grained-token");
        }

        current.set(secrets.replace(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", "rotated-token".toCharArray()));
        try (GitRemoteConnection second = profile.open(remote)) {
            HttpRequest.Builder request = HttpRequest.newBuilder(second.uri());
            captured.get().configure(request);
            assertThat(decodeBasic(request.build().headers().firstValue("Authorization").orElseThrow()))
                    .isEqualTo("x-access-token:rotated-token");
        }
        assertThat(resolutions).hasValue(2);
    }

    @Test
    void rejectsAnUnknownOwnerBeforeCreatingTheTransport() {
        GitHubRemoteProfile profile = new GitHubRemoteProfile(RepositoryAddress.parse("other/platform/api"),
                secrets, configurer -> {
                    throw new AssertionError("wrong owner must not create a transport");
                });

        assertThatThrownBy(() -> profile.open(remote(
                RemoteProvider.GITHUB, "https://github.com/acme/project.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("fine-grained-token");
    }

    private static RepositoryRemote remote(RemoteProvider provider, String uri) {
        return new RepositoryRemote(
                RemoteAlias.UPSTREAM,
                RemoteRole.PRIMARY,
                provider,
                URI.create(uri),
                new ConfigurationSecretReference(
                        ConfigurationSecretReference.Scope.REPOSITORY,
                        "github-token"),
                Set.of(
                        RemoteTrigger.STARTUP_RECONCILE,
                        RemoteTrigger.LOCAL_REF_UPDATE,
                        RemoteTrigger.PERIODIC_AUDIT,
                        RemoteTrigger.MANUAL_RETRY),
                List.of(RemoteRefMapping.allBranches()),
                RemoteUpdatePolicy.fastForwardOnly());
    }

    private static String decodeBasic(String authorization) {
        byte[] encoded = authorization.substring("Basic ".length())
                .getBytes(StandardCharsets.US_ASCII);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static final class UnusedTransport implements GitClientTransport {
        @Override
        public GitClientTransportSession open(
                GitClientService service,
                URI remoteUri,
                GitClientOptions options) throws GitClientTransportException {
            throw new AssertionError("transport must not be opened by the profile test");
        }
    }
}
