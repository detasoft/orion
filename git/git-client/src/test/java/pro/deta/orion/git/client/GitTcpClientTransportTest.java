package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitTcpClientTransportTest {
    @Test
    void rejectsNonGitUrisBeforeOpeningConnection() {
        GitTcpClientTransport transport = new GitTcpClientTransport();

        assertThatThrownBy(() -> transport.open(
                GitClientService.UPLOAD_PACK,
                URI.create("https://example.invalid/repository.git"),
                GitClientOptions.defaults()))
                .isInstanceOf(GitClientTransportException.class)
                .extracting(error -> ((GitClientTransportException) error).kind())
                .isEqualTo(GitClientFailure.Kind.PROTOCOL_UNSUPPORTED);
    }

    @Test
    void rejectsCredentialsInGitUri() {
        GitTcpClientTransport transport = new GitTcpClientTransport();

        assertThatThrownBy(() -> transport.open(
                GitClientService.UPLOAD_PACK,
                URI.create("git://secret@example.invalid/repository.git"),
                GitClientOptions.defaults()))
                .isInstanceOf(GitClientTransportException.class)
                .hasMessageNotContaining("secret");
    }
}
