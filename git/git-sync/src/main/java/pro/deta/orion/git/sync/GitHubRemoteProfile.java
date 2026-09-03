package pro.deta.orion.git.sync;

import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitHttpRequestConfigurer;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitSmartHttpClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RepositoryRemote;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

public final class GitHubRemoteProfile implements GitRemoteProfile {
    private static final String GITHUB_HOST = "github.com";
    private static final String USERNAME = "x-access-token";

    private final GitCredentialResolver credentials;
    private final TransportFactory transports;

    public GitHubRemoteProfile(GitCredentialResolver credentials) {
        this(
                credentials,
                configurer -> new GitSmartHttpClientTransport(
                        null,
                        configurer,
                        false));
    }

    GitHubRemoteProfile(
            GitCredentialResolver credentials,
            TransportFactory transports) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.transports = Objects.requireNonNull(transports, "transports");
    }

    @Override
    public GitRemoteConnection open(RepositoryRemote remote) {
        RepositoryRemote checked = requireGitHubRemote(remote);
        Secret secret = new Secret(credentials.resolve(checked.credential()));
        try {
            GitHttpRequestConfigurer configurer = request ->
                    request.header("Authorization", authorization(secret));
            GitClientTransport transport = transports.create(configurer);
            return new GitRemoteConnection(
                    checked.uri(),
                    GitClientOptions.defaults(),
                    new GitUploadPackClient(transport),
                    new GitReceivePackClient(transport),
                    secret::close);
        } catch (RuntimeException error) {
            secret.close();
            throw error;
        }
    }

    private static RepositoryRemote requireGitHubRemote(RepositoryRemote remote) {
        RepositoryRemote checked = Objects.requireNonNull(remote, "remote");
        if (checked.provider() != RemoteProvider.GITHUB) {
            throw new IllegalArgumentException("GitHub profile requires the GITHUB provider");
        }
        String host = checked.uri().getHost().toLowerCase(Locale.ROOT);
        if (!GITHUB_HOST.equals(host)) {
            throw new IllegalArgumentException("GitHub profile requires a github.com remote");
        }
        return checked;
    }

    private static String authorization(Secret secret) {
        char[] token = secret.copy();
        byte[] plain = null;
        try {
            plain = (USERNAME + ":" + new String(token))
                    .getBytes(StandardCharsets.UTF_8);
            return "Basic " + Base64.getEncoder().encodeToString(plain);
        } finally {
            Arrays.fill(token, '\0');
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    @FunctionalInterface
    interface TransportFactory {
        GitClientTransport create(GitHttpRequestConfigurer configurer);
    }

    private static final class Secret implements AutoCloseable {
        private char[] value;

        private Secret(char[] value) {
            char[] checked = Objects.requireNonNull(value, "credential");
            if (checked.length == 0) {
                throw new IllegalArgumentException("Git credential must not be empty");
            }
            this.value = checked.clone();
            Arrays.fill(checked, '\0');
        }

        private synchronized char[] copy() {
            if (value == null) {
                throw new IllegalStateException("Git credential is no longer available");
            }
            return value.clone();
        }

        @Override
        public synchronized void close() {
            if (value != null) {
                Arrays.fill(value, '\0');
                value = null;
            }
        }
    }
}
