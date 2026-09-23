package pro.deta.orion.git.proxy;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientService;
import pro.deta.orion.git.client.GitClientTransportException;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitSshClientTransport;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus.AUTHENTICATION_FAILED;
import static pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus.UNAVAILABLE;

class BootstrapGitProxyExceptionTest {
    @Test
    void retainsTheOriginalCauseAndItsSuppressedErrorsWithoutChangingTheOuterMessage() {
        IOException root = new IOException("upstream detail");
        IOException suppressed = new IOException("close failure");
        root.addSuppressed(suppressed);
        BootstrapGitProxyException exception = new BootstrapGitProxyException("upstream discovery",
                new GitClientFailure(GitClientFailure.Kind.AUTHENTICATION_FAILED, GitClientFailure.Phase.OPEN,
                        false, "client detail", root));

        assertThat(exception).hasMessage("Remote Git bootstrap failed during upstream discovery");
        assertThat(exception.status()).isEqualTo(AUTHENTICATION_FAILED);
        assertThat(exception.getCause()).isSameAs(root);
        assertThat(exception.getCause().getSuppressed()).containsExactly(suppressed);
    }

    @Test
    void preservesTheRejectedSshHostKeyInTheCauseChain() throws Exception {
        try (SshServer server = SshServer.setUpDefaultServer();
             GitCredentials credentials = GitCredentials.none()) {
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
            server.start();
            URI remote = URI.create("ssh://git@127.0.0.1:" + server.getPort() + "/repo");
            GitSshClientTransport transport = GitSshClientTransport.strictKnownHosts(Set.of(), credentials);
            GitClientTransportException rejected = catchThrowableOfType(GitClientTransportException.class,
                    () -> transport.open(GitClientService.UPLOAD_PACK, remote, GitClientOptions.defaults()));
            assertThat(rejected.kind()).isEqualTo(GitClientFailure.Kind.VERIFICATION_FAILED);
            BootstrapGitProxyException exception = new BootstrapGitProxyException("upstream discovery",
                    new GitClientFailure(rejected.kind(), GitClientFailure.Phase.OPEN,
                            rejected.retryable(), rejected.getMessage(), rejected));

            assertThat(exception.status()).isEqualTo(UNAVAILABLE);
            assertThat(exception.getCause()).isSameAs(rejected);
            assertThat(exception.getCause().getCause())
                    .isInstanceOfSatisfying(GitSshClientTransport.HostKeyRejectedException.class, key -> {
                        assertThat(key.host()).isEqualTo("127.0.0.1");
                        assertThat(key.port()).isEqualTo(server.getPort());
                        assertThat(key.serverKey()).isNotNull();
                    });
        }
    }

    @Test
    void allowsFailuresWithoutACause() {
        BootstrapGitProxyException exception = new BootstrapGitProxyException("upstream discovery",
                new GitClientFailure(GitClientFailure.Kind.SERVER_ERROR, GitClientFailure.Phase.ADVERTISEMENT,
                        false, "server error", null));
        assertThat(exception.status()).isEqualTo(UNAVAILABLE);
        assertThat(exception.getCause()).isNull();
    }
}
