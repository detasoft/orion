package pro.deta.orion.git.workflow.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionGitServerTest {
    @Test
    void ignoresAnObserverPathThatDisappearsBeforeCleanup(@TempDir Path directory) throws Exception {
        Path observer = Files.createDirectory(directory.resolve("observer"));
        Files.delete(observer);

        assertThatCode(() -> OrionGitServer.deleteRecursively(observer, Files::deleteIfExists))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresAFileThatDisappearsAtItsCleanupBoundary(@TempDir Path directory) throws Exception {
        Path observer = Files.createDirectory(directory.resolve("observer"));
        Path metadata = Files.writeString(observer.resolve("gc.log.lock"), "temporary");

        assertThatCode(() -> OrionGitServer.deleteRecursively(observer, path -> {
            Files.deleteIfExists(path);
            if (path.equals(metadata)) {
                throw new NoSuchFileException(path.toString());
            }
        })).doesNotThrowAnyException();
        assertThat(observer).doesNotExist();
    }

    @Test
    void aggregatesOtherCleanupFailuresAndContinues(@TempDir Path directory) throws Exception {
        Path observer = Files.createDirectory(directory.resolve("observer"));
        Path first = Files.writeString(observer.resolve("first"), "first");
        Path second = Files.writeString(observer.resolve("second"), "second");
        Set<Path> attempted = new LinkedHashSet<>();

        IOException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                IOException.class,
                () -> OrionGitServer.deleteRecursively(observer, path -> {
                    attempted.add(path);
                    Files.deleteIfExists(path);
                    if (path.equals(first) || path.equals(second)) {
                        throw new IOException("cannot delete " + path.getFileName());
                    }
                }));

        assertThat(attempted).contains(first, second);
        assertThat(failure).isNotNull();
        assertThat(failure.getSuppressed()).hasSize(1);
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"git", "http", "ssh"})
    void reusesAndRefreshesObserverStorageUntilClose(
            String transport, @TempDir Path directory) throws Exception {
        Path observer;
        try (GitServer server = matrixServer(transport);
                GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            String initial = commit(source, "initial\n");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            RepositorySnapshot first = server.snapshot(remote);
            assertThat(source.snapshot().difference(first)).isNull();
            assertThat(observerDirectories(directory)).hasSize(1);
            observer = observerDirectories(directory).getFirst();

            assertThat(first.difference(server.snapshot(remote))).isNull();
            commit(source, "updated\n");
            source.push("origin", "main");
            assertThat(source.snapshot().difference(server.snapshot(remote))).isNull();
            assertThat(first.refs()).containsEntry("refs/heads/main", initial);
            assertThat(observerDirectories(directory)).containsExactly(observer);
        }
        assertThat(observer).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"git", "http", "ssh"})
    void observesRewindsRewritesAndDeletedRefs(String transport, @TempDir Path directory) throws Exception {
        try (GitServer server = matrixServer(transport);
                GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"));
                GitWorkTree replacement = GitClients.jgitAllowAllSsh().init(directory.resolve("replacement"))) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            String initial = commit(source, "initial\n");
            source.updateRef("refs/heads/feature", initial);
            source.annotatedTag("release", initial);
            String second = commit(source, "second\n");
            source.addRemote("origin", remote);
            source.pushRefs("origin", "refs/heads/*:refs/heads/*", "refs/tags/*:refs/tags/*");
            assertThat(source.snapshot().difference(server.snapshot(remote))).isNull();

            source.updateRef("refs/heads/main", initial);
            source.pushRefs("origin", "+refs/heads/main:refs/heads/main");
            RepositorySnapshot rewound = server.snapshot(remote);
            assertThat(source.snapshot().difference(rewound)).isNull();
            assertThat(rewound.commits()).doesNotContainKey(second);

            commit(replacement, "replacement\n");
            replacement.addRemote("origin", remote);
            replacement.pushRefs("origin", "+refs/heads/main:refs/heads/main",
                    ":refs/heads/feature", ":refs/tags/release");
            RepositorySnapshot rewritten = server.snapshot(remote);
            assertThat(replacement.snapshot().difference(rewritten)).isNull();
            assertThat(rewritten.commits()).doesNotContainKeys(initial, second);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"git", "http", "ssh"})
    void refreshesAfterAllRemoteRefsAreDeleted(String transport, @TempDir Path directory) throws Exception {
        try (GitServer server = matrixServer(transport);
                GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"));
                GitWorkTree replacement = GitClients.jgitAllowAllSsh().init(directory.resolve("replacement"))) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            commit(source, "initial\n");
            source.updateRef("refs/heads/feature", "HEAD");
            source.addRemote("origin", remote);
            source.pushRefs("origin", "refs/heads/*:refs/heads/*");
            assertThat(source.snapshot().difference(server.snapshot(remote))).isNull();

            source.pushRefs("origin", ":refs/heads/main", ":refs/heads/feature");
            RepositorySnapshot empty = server.snapshot(remote);
            assertThat(empty.refs()).isEmpty();
            assertThat(empty.commits()).isEmpty();
            assertThat(empty.headSymref()).isEqualTo("refs/heads/main");

            commit(replacement, "replacement\n");
            replacement.addRemote("origin", remote);
            replacement.push("origin", "main");
            assertThat(replacement.snapshot().difference(server.snapshot(remote))).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"git", "http", "ssh"})
    void keepsRepositoryObserversIsolated(String transport, @TempDir Path directory) throws Exception {
        try (GitServer server = matrixServer(transport);
                GitWorkTree first = GitClients.jgitAllowAllSsh().init(directory.resolve("first-source"));
                GitWorkTree second = GitClients.jgitAllowAllSsh().init(directory.resolve("second-source"))) {
            GitRemoteRepository firstRemote = server.createRemoteRepository(directory, "first.git");
            GitRemoteRepository secondRemote = server.createRemoteRepository(directory, "second.git");
            commit(first, "first repository\n");
            first.addRemote("origin", firstRemote);
            first.push("origin", "main");
            assertThat(first.snapshot().difference(server.snapshot(firstRemote))).isNull();

            commit(second, "second repository\n");
            second.addRemote("origin", secondRemote);
            second.push("origin", "main");
            assertThat(second.snapshot().difference(server.snapshot(secondRemote))).isNull();
            assertThat(first.snapshot().difference(server.snapshot(firstRemote))).isNull();
            assertThat(observerDirectories(directory)).hasSize(2);
        }
        assertThat(observerDirectories(directory)).isEmpty();
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

    private static GitServer matrixServer(String transport) {
        return switch (transport) {
            case "git" -> OrionGitEngines.server();
            case "http" -> OrionGitEngines.httpServer();
            case "ssh" -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException("Unknown matrix transport: " + transport);
        };
    }

    private static String commit(GitWorkTree source, String content) throws Exception {
        source.writeFile("README.md", content);
        source.add("README.md");
        source.commit("update");
        return source.head();
    }

    private static List<Path> observerDirectories(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(".orion-jgit-observer-"))
                    .toList();
        }
    }
}
