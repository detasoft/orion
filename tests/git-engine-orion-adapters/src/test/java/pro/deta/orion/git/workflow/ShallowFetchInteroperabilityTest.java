package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class ShallowFetchInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (String client : List.of("git-v1", "git-v2", "jgit")) {
            for (GitTransportScheme scheme : List.of(GIT, HTTP, SSH)) {
                cases.add(Arguments.of(client, scheme));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} shallow fetch from Orion over {1}")
    @MethodSource("matrix")
    void clonesOneCommitThenDeepensAndUnshallows(String client, GitTransportScheme scheme) throws Exception {
        GitServer selected = switch (scheme) {
            case GIT -> OrionGitEngines.server();
            case HTTP -> OrionGitEngines.httpServer();
            case SSH -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException(scheme.name());
        };
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            List<String> commits = new ArrayList<>();
            for (int number = 1; number <= 4; number++) {
                source.writeFile("README.md", "version " + number + "\n");
                source.add("README.md");
                source.commit("commit " + number);
                commits.addFirst(source.head());
            }
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            RepositorySnapshot before = server.snapshot(remote);
            Path clone = directory.resolve("clone");
            GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
            String version = client.equals("git-v1") ? "protocol.version=1" : "protocol.version=2";
            if (client.equals("jgit")) {
                try (Git ignored = Git.cloneRepository().setURI(remote.uri()).setDirectory(clone.toFile())
                        .setDepth(1).setTransportConfigCallback(GitClients.allowAllSsh()).call()) {
                    assertHistory(git, clone, commits.subList(0, 1), true);
                }
            } else {
                git.run(null, "-c", version, "clone", "--depth=1", remote.uri(), clone.toString());
                assertHistory(git, clone, commits.subList(0, 1), true);
            }
            if (client.equals("jgit")) {
                try (Git fetched = Git.open(clone.toFile())) {
                    fetched.fetch().setDepth(2).setTransportConfigCallback(GitClients.allowAllSsh()).call();
                }
            } else {
                git.run(clone, "-c", version, "fetch", "--deepen=1", "origin");
            }
            assertHistory(git, clone, commits.subList(0, 2), true);
            assertThat(git.run(clone, "show", "HEAD~1:README.md").output()).isEqualTo("version 3\n");
            if (client.equals("jgit")) {
                try (Git fetched = Git.open(clone.toFile())) {
                    fetched.fetch().setUnshallow(true).setTransportConfigCallback(GitClients.allowAllSsh()).call();
                }
            } else {
                git.run(clone, "-c", version, "fetch", "--unshallow", "origin");
            }
            assertHistory(git, clone, commits, false);
            assertThat(git.run(clone, "show", "HEAD~3:README.md").output()).isEqualTo("version 1\n");
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }

    private static void assertHistory(GitCommandRunner git, Path clone, List<String> commits, boolean shallow)
            throws Exception {
        assertThat(git.run(clone, "rev-list", "HEAD").trimmed().lines().toList())
                .containsExactlyElementsOf(commits);
        assertThat(git.run(clone, "rev-parse", "--is-shallow-repository").trimmed())
                .isEqualTo(Boolean.toString(shallow));
        if (shallow) {
            assertThat(Files.readString(clone.resolve(".git/shallow")).strip()).isEqualTo(commits.getLast());
        }
        assertThat(git.run(clone, "show", "HEAD:README.md").output()).isEqualTo("version 4\n");
    }
}
