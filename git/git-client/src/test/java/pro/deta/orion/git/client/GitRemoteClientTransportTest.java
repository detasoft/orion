package pro.deta.orion.git.client;

import java.util.Set;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.schema.orion.GitCredentialKind;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRemoteClientTransportTest {
    @Test
    void selectsHttpBySchemeAndSendsBearerCredentials() throws Exception {
        assertHttpCredentials(GitCredentialKind.TOKEN, "", "token", "Bearer token");
    }

    @Test
    void sendsBasicCredentialsIncludingUtf8Characters() throws Exception {
        assertHttpCredentials(GitCredentialKind.PASSWORD, "user", "päss🔑",
                "Basic dXNlcjpww6Rzc/CflJE=");
    }

    @Test
    void allowsAnonymousHttpWithoutAuthorization() throws Exception {
        assertHttpCredentials(GitCredentialKind.NONE, "", "", null);
    }

    @Test
    void rejectsPrivateKeyForHttpBeforeSendingARequest() throws Exception {
        assertRejectedHttpCredentials(GitCredentialKind.PRIVATE_KEY, "",
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "user:name", "user\nname", "user\rname"})
    void rejectsInvalidBasicUsernameBeforeSendingARequest(String username) throws Exception {
        assertRejectedHttpCredentials(GitCredentialKind.PASSWORD, username,
                GitClientFailure.Kind.AUTHENTICATION_FAILED);
    }

    private static void assertRejectedHttpCredentials(
            GitCredentialKind kind, String username, GitClientFailure.Kind expected) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repository.git", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try (GitCredentials credentials = new GitCredentials(kind, username, "secret".toCharArray())) {
            GitClientTransport transport = new GitRemoteClientTransport(null, credentials, Set.of(), true);
            assertThatThrownBy(() -> transport.open(GitClientService.UPLOAD_PACK,
                    uri(server), GitClientOptions.defaults()))
                    .isInstanceOf(GitClientTransportException.class)
                    .extracting(error -> ((GitClientTransportException) error).kind())
                    .isEqualTo(expected);
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnknownOrMissingScheme() {
        GitClientTransport transport = new GitRemoteClientTransport(null, GitCredentials.none(), Set.of(), false);
        for (String location : new String[]{"ftp://example.test/repository.git", "repository.git"}) {
            assertThatThrownBy(() -> transport.open(
                    GitClientService.UPLOAD_PACK, URI.create(location), GitClientOptions.defaults()))
                    .isInstanceOf(GitClientTransportException.class)
                    .extracting(error -> ((GitClientTransportException) error).kind())
                    .isEqualTo(GitClientFailure.Kind.PROTOCOL_UNSUPPORTED);
        }
    }

    private static void assertHttpCredentials(
            GitCredentialKind kind, String username, String secret, String expected) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repository.git", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try (GitCredentials credentials = new GitCredentials(kind, username, secret.toCharArray())) {
            GitClientTransport transport = new GitRemoteClientTransport(null, credentials, Set.of(), true);
            GitClientResult<GitRemoteAdvertisement> result = new GitUploadPackClient(transport)
                    .discover(uri(server), GitClientOptions.defaults());
            assertThat(result).isInstanceOf(GitClientResult.Failed.class);
            assertThat(((GitClientResult.Failed<GitRemoteAdvertisement>) result).failure().kind())
                    .isEqualTo(GitClientFailure.Kind.AUTHENTICATION_FAILED);
            assertThat(authorization.get()).isEqualTo(expected);
        } finally {
            server.stop(0);
        }
    }

    private static URI uri(HttpServer server) {
        return URI.create("HTTP://127.0.0.1:" + server.getAddress().getPort() + "/repository.git");
    }
}
