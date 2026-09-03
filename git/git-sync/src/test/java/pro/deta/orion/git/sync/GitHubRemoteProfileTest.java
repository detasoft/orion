package pro.deta.orion.git.sync;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientService;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportException;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitHttpRequestConfigurer;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RemoteRefMapping;
import pro.deta.orion.schema.orion.RemoteRole;
import pro.deta.orion.schema.orion.RemoteTrigger;
import pro.deta.orion.schema.orion.RemoteUpdatePolicy;
import pro.deta.orion.schema.orion.RepositoryRemote;

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
    @Test
    void configuresGitHubHttpsWithAUsernameAndToken() {
        AtomicReference<GitHttpRequestConfigurer> captured = new AtomicReference<>();
        AtomicInteger resolutions = new AtomicInteger();
        GitHubRemoteProfile profile = new GitHubRemoteProfile(
                reference -> {
                    resolutions.incrementAndGet();
                    assertThat(reference.reference()).isEqualTo("github-token");
                    return "fine-grained-token".toCharArray();
                },
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
        AtomicInteger resolutions = new AtomicInteger();
        GitHubRemoteProfile profile = new GitHubRemoteProfile(
                reference -> {
                    resolutions.incrementAndGet();
                    return "secret-token".toCharArray();
                },
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
