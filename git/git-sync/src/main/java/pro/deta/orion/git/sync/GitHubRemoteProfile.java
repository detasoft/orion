package pro.deta.orion.git.sync;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitRemoteClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RepositoryAddress;
import pro.deta.orion.schema.orion.RepositoryRemote;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public final class GitHubRemoteProfile implements GitRemoteProfile {
    private static final String GITHUB_HOST = "github.com";
    private static final String USERNAME = "x-access-token";

    private final RepositoryAddress repository;
    private final ConfigurationSecrets credentials;
    private final TransportFactory transports;

    public GitHubRemoteProfile(RepositoryAddress repository, ConfigurationSecrets credentials) {
        this(
                repository,
                credentials,
                credential -> new GitRemoteClientTransport(null, credential, null, false));
    }

    GitHubRemoteProfile(
            RepositoryAddress repository,
            ConfigurationSecrets credentials,
            TransportFactory transports) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.transports = Objects.requireNonNull(transports, "transports");
    }

    @Override
    public GitRemoteConnection open(RepositoryRemote remote) {
        RepositoryRemote checked = requireGitHubRemote(remote);
        char[] characters = credentials.resolve(repository, checked.credential());
        GitCredentials credential;
        try {
            if (characters.length == 0) {
                throw new IllegalArgumentException("GitHub token must not be empty");
            }
            credential = new GitCredentials(GitCredentialKind.PASSWORD, USERNAME, characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
        try {
            GitClientTransport transport = transports.create(credential);
            return new GitRemoteConnection(
                    checked.uri(),
                    GitClientOptions.defaults(),
                    new GitUploadPackClient(transport),
                    new GitReceivePackClient(transport),
                    credential::close);
        } catch (RuntimeException error) {
            credential.close();
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

    @FunctionalInterface
    interface TransportFactory {
        GitClientTransport create(GitCredentials credentials);
    }
}
