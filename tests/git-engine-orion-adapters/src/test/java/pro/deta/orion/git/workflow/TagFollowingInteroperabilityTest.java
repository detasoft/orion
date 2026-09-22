package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class TagFollowingInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (String client : List.of("git-v1", "git-v2", "jgit")) {
            for (GitTransportScheme scheme : List.of(GIT, HTTP, SSH)) {
                for (boolean followTags : List.of(false, true)) {
                    cases.add(Arguments.of(client, scheme, followTags));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} fetch from Orion over {1}, follow tags: {2}")
    @MethodSource("matrix")
    void fetchesOnlyTagsOfTheRequestedHistoryWhenEnabled(String client, GitTransportScheme scheme,
                                                        boolean followTags) throws Exception {
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
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            Path clone = directory.resolve("clone");
            if (client.equals("jgit")) {
                try (Git cloned = Git.cloneRepository().setURI(remote.uri()).setDirectory(clone.toFile())
                        .setTransportConfigCallback(GitClients.allowAllSsh()).call()) {
                    assertThat(cloned.getRepository().resolve("HEAD").name()).isEqualTo(first);
                }
            } else {
                git.run(null, "-c", protocol, "clone", remote.uri(), clone.toString());
            }
            source.writeFile("README.md", "updated\n");
            source.add("README.md");
            source.commit("update");
            String second = source.head();
            String inner = source.annotatedTag("z-inner", second);
            String outer = source.annotatedTag("a-outer", inner);
            source.writeFile("README.md", "other branch\n");
            source.add("README.md");
            source.commit("other");
            String other = source.head();
            String unrelated = source.annotatedTag("unrelated", other);
            source.updateRef("refs/heads/other", other);
            source.updateRef("refs/heads/main", second);
            source.pushRefs("origin", "refs/heads/main:refs/heads/main", "refs/heads/other:refs/heads/other",
                    "refs/tags/z-inner:refs/tags/z-inner", "refs/tags/a-outer:refs/tags/a-outer",
                    "refs/tags/unrelated:refs/tags/unrelated");
            RepositorySnapshot before = server.snapshot(remote);

            if (client.equals("jgit")) {
                try (Git local = Git.open(clone.toFile())) {
                    local.fetch().setRemote("origin")
                            .setRefSpecs(new RefSpec("refs/heads/main:refs/remotes/origin/main"))
                            .setTagOpt(followTags ? TagOpt.AUTO_FOLLOW : TagOpt.NO_TAGS)
                            .setTransportConfigCallback(GitClients.allowAllSsh()).call();
                }
            } else {
                List<String> arguments = new ArrayList<>(List.of("-c", protocol, "fetch"));
                if (!followTags) {
                    arguments.add("--no-tags");
                }
                arguments.addAll(List.of("origin", "refs/heads/main:refs/remotes/origin/main"));
                git.run(clone, arguments.toArray(String[]::new));
            }
            assertThat(git.run(clone, "rev-parse", "HEAD").trimmed()).isEqualTo(first);
            assertThat(git.run(clone, "rev-list", "refs/remotes/origin/main").output().lines().toList())
                    .containsExactly(second, first);
            assertThat(git.run(clone, "show", "refs/remotes/origin/main:README.md").output()).isEqualTo("updated\n");
            try (Git local = Git.open(clone.toFile())) {
                Map<String, String> tags = new LinkedHashMap<>();
                for (Ref ref : local.getRepository().getRefDatabase().getRefsByPrefix("refs/tags/")) {
                    tags.put(ref.getName(), ref.getObjectId().name());
                }
                assertThat(tags).isEqualTo(followTags
                        ? Map.of("refs/tags/z-inner", inner, "refs/tags/a-outer", outer) : Map.of());
                for (String tag : List.of(inner, outer)) {
                    assertThat(local.getRepository().getObjectDatabase().has(ObjectId.fromString(tag)))
                            .as("tag object %s", tag).isEqualTo(followTags);
                }
                assertThat(local.getRepository().getObjectDatabase().has(ObjectId.fromString(unrelated))).isFalse();
                assertThat(local.getRepository().getObjectDatabase().has(ObjectId.fromString(other))).isFalse();
            }
            if (followTags) {
                assertThat(git.run(clone, "rev-parse", "refs/tags/a-outer^{}").trimmed()).isEqualTo(second);
            }
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }
}
