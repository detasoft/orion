package pro.deta.orion.git.workflow.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitWorkTree;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionGitServerTest {
    @Test
    void provisionsIsolatedMainRepositoriesOnOneLoopbackPort(@TempDir Path directory) throws Exception {
        try (GitServer server = OrionGitEngines.server()) {
            GitRemoteRepository first = server.createRemoteRepository(directory, "first.git");
            GitRemoteRepository second = server.createRemoteRepository(directory, "second.git");

            URI firstUri = URI.create(first.uri());
            URI secondUri = URI.create(second.uri());
            assertThat(firstUri.getScheme()).isEqualTo("git");
            assertThat(firstUri.getHost()).isEqualTo("127.0.0.1");
            assertThat(firstUri.getPort()).isPositive().isEqualTo(secondUri.getPort());
            assertThat(firstUri.getPath()).isEqualTo("/first.git");
            assertThat(secondUri.getPath()).isEqualTo("/second.git");
            assertThat(server.snapshot(first).headSymref()).isEqualTo("refs/heads/main");
            assertThat(server.snapshot(first).refs()).isEmpty();
            try (var paths = Files.walk(directory)) {
                assertThat(paths.map(path -> path.getFileName().toString()))
                        .contains("orion-native-repository.properties");
            }
            assertThat(server.diagnostics())
                    .contains("127.0.0.1:" + firstUri.getPort())
                    .contains("running=true");
        }
    }

    @Test
    void independentlyObservesJGitPushAndStopsDeterministically(@TempDir Path directory) throws Exception {
        GitServer server = OrionGitEngines.server();
        GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
        int port = URI.create(remote.uri()).getPort();
        try {
            try (GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
                source.writeFile("README.md", "through Orion\n");
                source.add("README.md");
                source.commit("initial");
                source.addRemote("origin", remote);
                source.push("origin", "main");
                assertThat(server.snapshot(remote).refs())
                        .containsEntry("refs/heads/main", source.head());
            }
        } finally {
            server.close();
        }

        assertThat(server.diagnostics())
                .contains("127.0.0.1:" + port)
                .contains("running=false");
        assertThatThrownBy(() -> {
            try (var ignored = new java.net.Socket("127.0.0.1", port)) {
            }
        }).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsProvisioningOutsideTheFirstInvocationRoot(@TempDir Path directory) throws Exception {
        try (GitServer server = OrionGitEngines.server()) {
            server.createRemoteRepository(directory.resolve("one"), "remote.git");

            assertThatThrownBy(() -> server.createRemoteRepository(directory.resolve("two"), "other.git"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("isolated root");
        }
    }

    @Test
    void closeIsTerminalAndIdempotentAfterStart(@TempDir Path directory) throws Exception {
        GitServer server = OrionGitEngines.server();
        GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");

        server.close();
        server.close();

        assertThatThrownBy(() -> server.createRemoteRepository(directory, "other.git"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed")
                .hasMessageContaining("provision");
        assertThatThrownBy(() -> server.snapshot(remote))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed")
                .hasMessageContaining("snapshot");
        assertThat(server.diagnostics()).contains("closed=true");
    }

    @Test
    void closeBeforeStartPreventsLaterProvisioning(@TempDir Path directory) throws Exception {
        GitServer server = OrionGitEngines.server();

        server.close();
        server.close();

        assertThatThrownBy(() -> server.createRemoteRepository(directory, "remote.git"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed")
                .hasMessageContaining("provision");
        assertThat(server.diagnostics())
                .contains("running=false")
                .contains("closed=true")
                .contains("storage=uninitialized");
    }
}
