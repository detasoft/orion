package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRemoteClientTransportTest {
    @Test
    void selectsTransportByRepositoryScheme() throws Exception {
        List<String> selected = new ArrayList<>();
        GitClientTransport tcp = recording("tcp", selected);
        GitClientTransport ssh = recording("ssh", selected);
        GitClientTransport http = recording("http", selected);
        GitRemoteClientTransport transport = new GitRemoteClientTransport(
                tcp, ssh, http);

        transport.open(GitClientService.UPLOAD_PACK,
                URI.create("git://example.test/repository.git"),
                GitClientOptions.defaults());
        transport.open(GitClientService.UPLOAD_PACK,
                URI.create("ssh://git@example.test/repository.git"),
                GitClientOptions.defaults());
        transport.open(GitClientService.UPLOAD_PACK,
                URI.create("https://example.test/repository.git"),
                GitClientOptions.defaults());

        assertThat(selected).containsExactly("tcp", "ssh", "http");
    }

    @Test
    void rejectsUnknownScheme() {
        GitClientTransport unused = recording("unused", new ArrayList<>());
        GitRemoteClientTransport transport = new GitRemoteClientTransport(
                unused, unused, unused);

        assertThatThrownBy(() -> transport.open(
                GitClientService.UPLOAD_PACK,
                URI.create("ftp://example.test/repository.git"),
                GitClientOptions.defaults()))
                .isInstanceOf(GitClientTransportException.class)
                .extracting(error -> ((GitClientTransportException) error).kind())
                .isEqualTo(GitClientFailure.Kind.PROTOCOL_UNSUPPORTED);
    }

    private static GitClientTransport recording(
            String name,
            List<String> selected) {
        return (service, uri, options) -> {
            selected.add(name);
            return new EmptySession();
        };
    }

    private static final class EmptySession implements GitClientTransportSession {
        @Override
        public pro.deta.orion.net.io.BufferedByteInput input() {
            throw new UnsupportedOperationException();
        }

        @Override
        public pro.deta.orion.net.io.BufferedByteOutput output() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
