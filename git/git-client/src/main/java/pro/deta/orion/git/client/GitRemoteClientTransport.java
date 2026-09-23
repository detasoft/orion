package pro.deta.orion.git.client;

import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.Set;

/**
 * Selects the transport exclusively from the URI scheme. Credentials are interpreted by that transport;
 * supplied HTTP clients and credentials remain caller-owned. SSH sessions own their strict known-host client.
 */
public final class GitRemoteClientTransport implements GitClientTransport {
    private final HttpClient httpClient;
    private final GitCredentials credentials;
    private final Set<String> knownHosts;
    private final boolean allowPlainHttp;

    public GitRemoteClientTransport(
            HttpClient httpClient, GitCredentials credentials, Set<String> knownHosts, boolean allowPlainHttp) {
        this.httpClient = httpClient;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.knownHosts = GitProxyBinding.canonicalKnownHosts(knownHosts);
        this.allowPlainHttp = allowPlainHttp;
    }

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        credentials.requireOpen();
        GitClientTransport transport = switch (GitTransportScheme.from(remoteUri)) {
            case FILE -> {
                credentials.requireKind(GitCredentialKind.NONE);
                yield new GitFileClientTransport();
            }
            case GIT -> {
                credentials.requireKind(GitCredentialKind.NONE);
                yield new GitTcpClientTransport();
            }
            case SSH -> GitSshClientTransport.strictKnownHosts(knownHosts, credentials);
            case HTTP, HTTPS -> new GitSmartHttpClientTransport(httpClient, credentials, allowPlainHttp);
        };
        return transport.open(service, remoteUri, options);
    }
}
