package pro.deta.orion.git.workflow.orion;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PackVersionInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (GitTransportScheme scheme : List.of(GitTransportScheme.GIT,
                GitTransportScheme.HTTP, GitTransportScheme.SSH)) {
            for (String reader : List.of("git", "jgit", "orion")) {
                cases.add(Arguments.of(scheme, reader));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "pack v3 over {0}, clone with {1}")
    @MethodSource("matrix")
    void acceptsV3PushAndServesThePublishedHistory(GitTransportScheme scheme, String reader) throws Exception {
        OrionGitClient sender = (OrionGitClient) switch (scheme) {
            case GIT -> OrionGitEngines.client();
            case HTTP -> OrionGitEngines.httpClient();
            case SSH -> OrionGitEngines.sshClient();
            default -> throw new IllegalArgumentException("Unsupported test transport: " + scheme);
        };
        GitClient consumer = switch (reader) {
            case "git" -> GitClients.git();
            case "jgit" -> GitClients.jgitAllowAllSsh();
            case "orion" -> sender;
            default -> throw new IllegalArgumentException("Unknown reader: " + reader);
        };
        try (GitServer server = new OrionGitServer(scheme);
             GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
            source.writeFile("README.md", "initial\n");
            source.add("README.md");
            source.commit("initial");
            source.writeFile("README.md", "updated\n");
            source.add("README.md");
            source.commit("second");
            RepositorySnapshot expected = source.snapshot();
            byte[] pack = versionThreePack(source);
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            GitReceivePackRequest request = new GitReceivePackRequest(List.of(
                    new GitReceivePackRequest.Command("0".repeat(40), source.head(), "refs/heads/main")),
                    output -> output.write(pack));
            OrionGitClient.requireAccepted(OrionGitClient.requireSuccess(
                    sender.receivePack().push(sender.uri(remote), sender.options(), request), "pack v3 push"));
            assertThat(expected.difference(server.snapshot(remote))).isNull();
            try (GitWorkTree clone = consumer.clone(remote, directory.resolve("clone"))) {
                assertThat(expected.difference(clone.snapshot())).isNull();
            }
        }
    }

    private static byte[] versionThreePack(GitWorkTree source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Git git = Git.open(source.directory().toFile());
             PackWriter writer = new PackWriter(git.getRepository())) {
            writer.preparePack(NullProgressMonitor.INSTANCE,
                    Set.of(ObjectId.fromString(source.head())), Set.of());
            writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, output);
        }
        byte[] pack = output.toByteArray();
        assertThat(ByteBuffer.wrap(pack).getInt(4)).isEqualTo(2);
        ByteBuffer.wrap(pack).putInt(4, 3);
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        hash.update(pack, 0, pack.length - 20);
        System.arraycopy(hash.digest(), 0, pack, pack.length - 20, 20);
        return pack;
    }
}
