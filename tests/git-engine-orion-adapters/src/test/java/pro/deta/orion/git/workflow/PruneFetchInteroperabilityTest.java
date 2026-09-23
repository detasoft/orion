package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
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

class PruneFetchInteroperabilityTest {
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

    @ParameterizedTest(name = "{0} fetch --prune from Orion over {1}")
    @MethodSource("matrix")
    void prunesDeletedRemoteBranchWhilePreservingLocalBranchAndTags(String client,
                                                                   GitTransportScheme scheme) throws Exception {
        GitServer selected = switch (scheme) {
            case GIT -> OrionGitEngines.server();
            case HTTP -> OrionGitEngines.httpServer();
            case SSH -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException(scheme.name());
        };
        GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
        String protocol = client.equals("git-v1") ? "protocol.version=1" : "protocol.version=2";
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            source.writeFile("README.md", "initial\n");
            source.add("README.md");
            source.commit("initial");
            String first = source.head();
            source.updateRef("refs/heads/feature", first);
            String tag = source.annotatedTag("release", first);
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.pushRefs("origin", "refs/heads/main:refs/heads/main", "refs/heads/feature:refs/heads/feature",
                    "refs/tags/release:refs/tags/release");
            Path clone = directory.resolve("clone");
            if (client.equals("jgit")) {
                try (Git cloned = Git.cloneRepository().setURI(remote.uri()).setDirectory(clone.toFile())
                        .setTransportConfigCallback(GitClients.allowAllSsh()).call()) {
                    assertThat(cloned.getRepository().resolve("HEAD").name()).isEqualTo(first);
                }
            } else {
                git.run(null, "-c", protocol, "clone", remote.uri(), clone.toString());
            }
            git.run(clone, "checkout", "-b", "feature", "--track", "origin/feature");
            assertThat(git.run(clone, "rev-parse", "refs/tags/release").trimmed()).isEqualTo(tag);

            source.writeFile("README.md", "updated\n");
            source.add("README.md");
            source.commit("update");
            String second = source.head();
            source.pushRefs("origin", "refs/heads/main:refs/heads/main", ":refs/heads/feature", ":refs/tags/release");
            assertThat(source.advertisedRefs("origin"))
                    .doesNotContainKeys("refs/heads/feature", "refs/tags/release");
            RepositorySnapshot before = server.snapshot(remote);

            for (boolean prune : List.of(false, true)) {
                if (client.equals("jgit")) {
                    try (Git local = Git.open(clone.toFile())) {
                        local.fetch().setRemote("origin").setRemoveDeletedRefs(prune)
                                .setTransportConfigCallback(GitClients.allowAllSsh()).call();
                    }
                } else {
                    git.run(clone, "-c", protocol, "fetch", prune ? "--prune" : "--no-prune", "origin");
                }
                try (Git local = Git.open(clone.toFile())) {
                    assertThat(local.getRepository().resolve("refs/remotes/origin/feature"))
                            .isEqualTo(prune ? null : ObjectId.fromString(first));
                }
                assertThat(git.run(clone, "symbolic-ref", "HEAD").trimmed()).isEqualTo("refs/heads/feature");
                assertThat(git.run(clone, "rev-parse", "refs/heads/feature").trimmed()).isEqualTo(first);
                assertThat(git.run(clone, "rev-parse", "refs/heads/main").trimmed()).isEqualTo(first);
                assertThat(git.run(clone, "rev-parse", "refs/tags/release").trimmed()).isEqualTo(tag);
                assertThat(git.run(clone, "rev-list", "refs/remotes/origin/main").output().lines().toList())
                        .containsExactly(second, first);
                assertThat(git.run(clone, "show", "refs/remotes/origin/main:README.md").output())
                        .isEqualTo("updated\n");
                assertThat(Files.readString(clone.resolve("README.md"))).isEqualTo("initial\n");
                assertThat(before.difference(server.snapshot(remote))).isNull();
            }
            git.run(clone, "fsck", "--full");
        }
    }
}
