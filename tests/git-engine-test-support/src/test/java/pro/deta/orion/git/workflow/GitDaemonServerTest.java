package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitDaemonServerTest {
    @Test
    void servesReceivePackFromAnIsolatedRootOnADynamicPort(@TempDir Path directory) throws Exception {
        GitServer server = GitServers.git();

        try (server) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            URI uri = URI.create(remote.uri());
            assertThat(uri.getHost()).isEqualTo("127.0.0.1");
            assertThat(uri.getPort()).isPositive();

            try (GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
                source.writeFile("README.md", "canonical daemon\n");
                source.add("README.md");
                source.commit("initial");
                source.addRemote("origin", remote);
                source.push("origin", "main");
            }

            assertThat(server.snapshot(remote).refs()).containsKey("refs/heads/main");
            assertThat(server.diagnostics()).contains("git version ").contains("127.0.0.1:");
        }
    }

    @Test
    void rejectsRepositoriesOutsideItsRoot(@TempDir Path directory) throws Exception {
        try (GitServer server = GitServers.git()) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> server.createRemoteRepository(directory, "../escaped.git"))
                    .withMessageContaining("one path segment");
        }
    }

    @Test
    void retriesWithAnotherDynamicPortAfterABindCollision(@TempDir Path directory) throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int available = availablePortOtherThan(occupied.getLocalPort());
            int[] ports = {occupied.getLocalPort(), available};
            AtomicInteger index = new AtomicInteger();

            try (GitServer server = new GitDaemonServer("git", () -> ports[index.getAndIncrement()])) {
                GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");

                assertThat(URI.create(remote.uri()).getPort()).isEqualTo(available);
                assertThat(server.diagnostics()).contains("start attempt 1").contains("start attempt 2");
            }
        }
    }

    private static int availablePortOtherThan(int excluded) throws Exception {
        int port;
        do {
            try (ServerSocket candidate = new ServerSocket()) {
                candidate.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                port = candidate.getLocalPort();
            }
        } while (port == excluded);
        return port;
    }
}
