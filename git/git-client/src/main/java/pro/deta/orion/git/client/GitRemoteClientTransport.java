package pro.deta.orion.git.client;

import pro.deta.orion.schema.orion.GitCredentialKind;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Selects the transport exclusively from the URI scheme. Credentials are interpreted by that transport;
 * supplied HTTP clients and credentials remain caller-owned. SSH sessions own their strict known-host client.
 */
public final class GitRemoteClientTransport implements GitClientTransport {
    private final HttpClient httpClient;
    private final GitCredentials credentials;
    private final Path knownHosts;
    private final boolean allowPlainHttp;

    public GitRemoteClientTransport(
            HttpClient httpClient, GitCredentials credentials, Path knownHosts, boolean allowPlainHttp) {
        this.httpClient = httpClient;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.knownHosts = knownHosts;
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
            case SSH -> {
                if (knownHosts == null) {
                    throw unsupported("Git SSH transport requires known-hosts configuration");
                }
                yield GitSshClientTransport.strictKnownHosts(knownHosts, credentials);
            }
            case HTTP, HTTPS -> new GitSmartHttpClientTransport(httpClient, credentials, allowPlainHttp);
        };
        return transport.open(service, remoteUri, options);
    }

    private static GitClientTransportException unsupported(String message) {
        return new GitClientTransportException(GitClientFailure.Kind.PROTOCOL_UNSUPPORTED, false, message);
    }
}
