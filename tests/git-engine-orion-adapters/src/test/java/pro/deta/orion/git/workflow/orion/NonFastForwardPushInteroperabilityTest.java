package pro.deta.orion.git.workflow.orion;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitTcpClientTransport;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NonFastForwardPushInteroperabilityTest {
    private static final String MAIN = "refs/heads/main";
    private static final String NEXT = "refs/heads/next";
    private static final String REPLACEMENT = "refs/heads/replacement";

    @TempDir
    Path directory;

    @ParameterizedTest(name = "Orion push obeys {0} non-fast-forward policy")
    @ValueSource(strings = {"git", "jgit"})
    void rejectsHistoryReplacementUntilServerPolicyIsDisabled(String engine) throws Exception {
        GitServer selected = switch (engine) {
            case "git" -> GitServers.git();
            case "jgit" -> GitServers.jgit();
            default -> throw new IllegalArgumentException(engine);
        };
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgit().init(directory.resolve("source"));
             GitWorkTree replacement = GitClients.jgit().init(directory.resolve("replacement"))) {
            String first = commit(source, "first");
            String next = commit(source, "next");
            source.updateRef(NEXT, next);
            source.updateRef(MAIN, first);
            String unrelated = commit(replacement, "unrelated");
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.pushRefs("origin", MAIN + ":" + MAIN, NEXT + ":" + NEXT);
            replacement.addRemote("origin", remote);
            replacement.pushRefs("origin", MAIN + ":" + REPLACEMENT);
            RepositorySnapshot initial = server.snapshot(remote);
            assertThat(initial.refs()).isEqualTo(Map.of(MAIN, first, NEXT, next, REPLACEMENT, unrelated));
            assertThat(initial.commits().get(first).parents()).isEmpty();
            assertThat(initial.commits().get(next).parents()).containsExactly(first);
            assertThat(initial.commits().get(unrelated).parents()).isEmpty();

            GitReceivePackClient client = new GitReceivePackClient(new GitTcpClientTransport());
            denyNonFastForwards(remote, true);
            GitReceivePackResult fastForward = push(client, remote, first, next);
            assertThat(fastForward.accepted()).isTrue();
            RepositorySnapshot beforeRejection = server.snapshot(remote);
            RepositorySnapshot expected = RepositorySnapshot.of(initial.headSymref(),
                    Map.of(MAIN, next, NEXT, next, REPLACEMENT, unrelated), initial.commits());
            assertThat(expected.difference(beforeRejection)).isNull();

            GitReceivePackResult rejected = push(client, remote, next, unrelated);
            assertThat(rejected.unpackStatus()).isEqualTo("ok");
            assertThat(rejected.accepted()).isFalse();
            assertThat(rejected.refs()).singleElement().satisfies(ref -> {
                assertThat(ref.refName()).isEqualTo(MAIN);
                assertThat(ref.accepted()).isFalse();
                assertThat(ref.message()).isNotBlank();
            });
            assertThat(beforeRejection.difference(server.snapshot(remote))).isNull();

            denyNonFastForwards(remote, false);
            GitReceivePackResult accepted = push(client, remote, next, unrelated);
            assertThat(accepted.accepted()).isTrue();
            RepositorySnapshot replaced = RepositorySnapshot.of(initial.headSymref(),
                    Map.of(MAIN, unrelated, NEXT, next, REPLACEMENT, unrelated), initial.commits());
            assertThat(replaced.difference(server.snapshot(remote))).isNull();
        }
    }

    private static String commit(GitWorkTree workTree, String content) throws Exception {
        workTree.writeFile("README.md", content + "\n");
        workTree.add("README.md");
        workTree.commit(content);
        return workTree.head();
    }

    private static void denyNonFastForwards(GitRemoteRepository remote, boolean deny) throws Exception {
        try (Repository repository = new FileRepositoryBuilder().setGitDir(remote.directory().toFile()).build()) {
            StoredConfig config = repository.getConfig();
            config.setBoolean("receive", null, "denyNonFastForwards", deny);
            config.save();
        }
    }

    private static GitReceivePackResult push(GitReceivePackClient client, GitRemoteRepository remote,
            String oldId, String newId) {
        GitReceivePackRequest request = new GitReceivePackRequest(
                List.of(new GitReceivePackRequest.Command(oldId, newId, MAIN)), output -> {
                    try (PackWriter pack = new PackWriter(output, 0)) {
                        pack.finish();
                    }
                });
        return OrionGitClient.requireSuccess(client.push(URI.create(remote.uri()),
                GitClientOptions.defaults(), request), "receive-pack");
    }
}
