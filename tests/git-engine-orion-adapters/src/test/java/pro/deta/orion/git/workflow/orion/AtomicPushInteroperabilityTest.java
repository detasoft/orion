package pro.deta.orion.git.workflow.orion;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitSmartHttpClientTransport;
import pro.deta.orion.git.client.GitSshClientTransport;
import pro.deta.orion.git.client.GitTcpClientTransport;
import pro.deta.orion.git.client.GitTransportScheme;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class AtomicPushInteroperabilityTest {
    private static final String MAIN = "refs/heads/main";
    private static final String LEFT = "refs/heads/left";
    private static final String RIGHT = "refs/heads/right";

    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        return Stream.of(Arguments.of("orion", GIT), Arguments.of("orion", HTTP),
                Arguments.of("orion", SSH), Arguments.of("git", GIT), Arguments.of("jgit", GIT));
    }

    @ParameterizedTest(name = "Orion atomic push to {0} over {1}")
    @MethodSource("matrix")
    void commitsBothRefsOrRejectsBothWhenOneOldIdIsStale(String engine, GitTransportScheme scheme)
            throws Exception {
        GitServer selected = switch (engine) {
            case "orion" -> new OrionGitServer(scheme);
            case "git" -> GitServers.git();
            case "jgit" -> GitServers.jgit();
            default -> throw new IllegalArgumentException(engine);
        };
        try (GitServer server = selected;
             SshClient ssh = SshClient.setUpDefaultClient();
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            ssh.setServerKeyVerifier((session, address, key) -> true);
            if (scheme == SSH) {
                ssh.start();
            }
            GitClientTransport transport = switch (scheme) {
                case GIT -> new GitTcpClientTransport();
                case HTTP -> new GitSmartHttpClientTransport(null, GitCredentials.none(), true);
                case SSH -> new GitSshClientTransport(ssh, GitCredentials.none());
                default -> throw new IllegalArgumentException(scheme.name());
            };
            GitReceivePackClient client = new GitReceivePackClient(transport);
            String first = commit(source, "first");
            source.updateRef(LEFT, first);
            source.updateRef(RIGHT, first);
            String second = commit(source, "second");
            String third = commit(source, "third");
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.pushRefs("origin", MAIN + ":" + MAIN, LEFT + ":" + LEFT, RIGHT + ":" + RIGHT);
            RepositorySnapshot initial = server.snapshot(remote);
            assertThat(initial.refs()).isEqualTo(Map.of(MAIN, third, LEFT, first, RIGHT, first));

            GitReceivePackResult accepted = push(client, remote, true,
                    new GitReceivePackRequest.Command(first, second, LEFT),
                    new GitReceivePackRequest.Command(first, second, RIGHT));
            assertThat(accepted.advertisement().capabilities()).contains("atomic");
            assertThat(accepted.accepted()).isTrue();
            RepositorySnapshot beforeFailure = server.snapshot(remote);
            assertThat(beforeFailure.refs()).isEqualTo(Map.of(MAIN, third, LEFT, second, RIGHT, second));
            assertThat(beforeFailure.commits()).isEqualTo(initial.commits());

            GitReceivePackRequest.Command valid = new GitReceivePackRequest.Command(second, third, LEFT);
            GitReceivePackRequest.Command stale = new GitReceivePackRequest.Command(first, third, RIGHT);
            GitReceivePackResult rejected = push(client, remote, true, valid, stale);
            assertThat(rejected.unpackStatus()).isEqualTo("ok");
            assertThat(rejected.accepted()).isFalse();
            assertThat(rejected.refs()).extracting(GitReceivePackResult.RefStatus::refName)
                    .containsExactlyInAnyOrder(LEFT, RIGHT);
            assertThat(rejected.refs()).allSatisfy(ref -> assertThat(ref.accepted()).isFalse());
            assertThat(beforeFailure.difference(server.snapshot(remote))).isNull();

            GitReceivePackResult partial = push(client, remote, false, valid, stale);
            assertThat(partial.refs()).filteredOn(ref -> ref.refName().equals(LEFT))
                    .singleElement().satisfies(ref -> assertThat(ref.accepted()).isTrue());
            assertThat(partial.refs()).filteredOn(ref -> ref.refName().equals(RIGHT))
                    .singleElement().satisfies(ref -> assertThat(ref.accepted()).isFalse());
            RepositorySnapshot afterPartial = server.snapshot(remote);
            assertThat(afterPartial.refs()).isEqualTo(Map.of(MAIN, third, LEFT, third, RIGHT, second));
            assertThat(afterPartial.commits()).isEqualTo(initial.commits());
        }
    }

    private static String commit(GitWorkTree source, String content) throws Exception {
        source.writeFile("README.md", content + "\n");
        source.add("README.md");
        source.commit(content);
        return source.head();
    }

    private static GitReceivePackResult push(GitReceivePackClient client, GitRemoteRepository remote,
            boolean atomic, GitReceivePackRequest.Command... commands) {
        GitReceivePackRequest request = new GitReceivePackRequest(List.of(commands), output -> {
            try (PackWriter pack = new PackWriter(output, 0)) {
                pack.finish();
            }
        }, atomic);
        return OrionGitClient.requireSuccess(client.push(URI.create(remote.uri()),
                GitClientOptions.defaults(), request), "atomic receive-pack");
    }
}
