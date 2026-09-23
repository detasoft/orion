package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class ForceWithLeaseInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (String client : List.of("git", "jgit")) {
            for (GitTransportScheme scheme : List.of(GIT, HTTP, SSH)) {
                cases.add(Arguments.of(client, scheme));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} force-with-lease to Orion over {1}")
    @MethodSource("matrix")
    void rejectsStaleLeaseAndAllowsExplicitReplacement(String client, GitTransportScheme scheme) throws Exception {
        GitServer selected = switch (scheme) {
            case GIT -> OrionGitEngines.server();
            case HTTP -> OrionGitEngines.httpServer();
            case SSH -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException(scheme.name());
        };
        GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
        GitClient selectedClient = client.equals("jgit") ? GitClients.jgitAllowAllSsh() : GitClients.git();
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            source.writeFile("README.md", "initial\n");
            source.add("README.md");
            source.commit("initial");
            String first = source.head();
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            try (GitWorkTree local = selectedClient.clone(remote, directory.resolve("clone"))) {
                source.writeFile("README.md", "winner\n");
                source.add("README.md");
                source.commit("winner");
                String winner = source.head();
                source.push("origin", "main");
                RepositorySnapshot before = server.snapshot(remote);

                local.writeFile("README.md", "replacement\n");
                local.add("README.md");
                local.commit("replacement");
                RepositorySnapshot replacement = local.snapshot();
                assertThat(git.run(local.directory(), "rev-parse", "refs/remotes/origin/main").trimmed())
                        .isEqualTo(first);
                for (boolean currentLease : List.of(false, true)) {
                    String expected = currentLease ? winner : first;
                    if (client.equals("jgit")) {
                        try (Git repository = Git.open(local.directory().toFile())) {
                            Iterable<PushResult> results = repository.push().setRemote("origin")
                                    .setRefSpecs(new RefSpec("+refs/heads/main:refs/heads/main"))
                                    .setRefLeaseSpecs(new RefLeaseSpec("refs/heads/main", expected))
                                    .setTransportConfigCallback(GitClients.allowAllSsh()).call();
                            assertThat(results).singleElement().satisfies(result -> {
                                assertThat(result.getRemoteUpdates()).hasSize(1);
                                assertThat(result.getRemoteUpdate("refs/heads/main").getStatus())
                                        .isEqualTo(currentLease ? RemoteRefUpdate.Status.OK
                                                : RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED);
                            });
                        }
                    } else {
                        GitCommandRunner.Result result = git.runResult(local.directory(), "push", "--porcelain",
                                "--force-with-lease=refs/heads/main:" + expected,
                                "origin", "refs/heads/main:refs/heads/main");
                        assertThat(result.successful()).as(result.output()).isEqualTo(currentLease);
                        assertThat(result.output()).contains(currentLease ? "forced update" : "stale info");
                    }
                    RepositorySnapshot expectedState = currentLease ? replacement : before;
                    assertThat(expectedState.difference(server.snapshot(remote))).isNull();
                    assertThat(replacement.difference(local.snapshot())).isNull();
                }
            }
        }
    }
}
