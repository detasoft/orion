package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                for (String boundary : List.of("depth", "since", "exclude")) {
                    cases.add(Arguments.of(client, scheme, boundary));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} shallow fetch from Orion over {1}, boundary: {2}")
    @MethodSource("matrix")
    void clonesShallowHistoryThenDeepensAndUnshallows(String client, GitTransportScheme scheme, String boundary)
            throws Exception {
        GitServer selected = switch (scheme) {
            case GIT -> OrionGitEngines.server();
            case HTTP -> OrionGitEngines.httpServer();
            case SSH -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException(scheme.name());
        };
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
            List<String> commits = new ArrayList<>();
            for (int number = 1; number <= 4; number++) {
                source.writeFile("README.md", "version " + number + "\n");
                source.add("README.md");
                String date = "2024-01-0" + number + "T00:00:00Z";
                git.run(source.directory(), Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date),
                        "commit", "-m", "commit " + number);
                commits.addFirst(source.head());
            }
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            if (boundary.equals("exclude")) {
                source.updateRef("refs/heads/excluded", commits.get(2));
                source.pushRefs("origin", "refs/heads/excluded:refs/heads/excluded");
            }
            RepositorySnapshot before = server.snapshot(remote);
            Path clone = directory.resolve("clone");
            int initialDepth = boundary.equals("depth") ? 1 : 2;
            String cutoff = "2024-01-02T12:00:00Z";
            String version = client.equals("git-v1") ? "protocol.version=1" : "protocol.version=2";
            if (client.equals("jgit")) {
                CloneCommand command = Git.cloneRepository().setURI(remote.uri()).setDirectory(clone.toFile())
                        .setBranch("main").setBranchesToClone(List.of("refs/heads/main"))
                        .setTransportConfigCallback(GitClients.allowAllSsh());
                switch (boundary) {
                    case "depth" -> command.setDepth(1);
                    case "since" -> command.setShallowSince(Instant.parse(cutoff));
                    case "exclude" -> command.addShallowExclude("refs/heads/excluded");
                    default -> throw new IllegalArgumentException(boundary);
                }
                try (Git ignored = command.call()) {
                    assertHistory(git, clone, commits.subList(0, initialDepth), true);
                }
            } else {
                String option = switch (boundary) {
                    case "depth" -> "--depth=1";
                    case "since" -> "--shallow-since=" + cutoff;
                    case "exclude" -> "--shallow-exclude=refs/heads/excluded";
                    default -> throw new IllegalArgumentException(boundary);
                };
                git.run(null, "-c", version, "clone", "--single-branch", "--branch=main", option,
                        remote.uri(), clone.toString());
                assertHistory(git, clone, commits.subList(0, initialDepth), true);
            }
            if (client.equals("jgit")) {
                try (Git fetched = Git.open(clone.toFile())) {
                    fetched.fetch().setDepth(initialDepth + 1).setTransportConfigCallback(GitClients.allowAllSsh()).call();
                }
            } else {
                git.run(clone, "-c", version, "fetch", "--deepen=1", "origin");
            }
            assertHistory(git, clone, commits.subList(0, initialDepth + 1), true);
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
