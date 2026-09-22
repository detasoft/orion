package pro.deta.orion.git.workflow.orion;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.HTTP;

class PushDiscoveryInteroperabilityTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "HTTP first push without discovery, protocol offer {0}")
    @EnumSource(GitProtocolVersion.class)
    void receivesFirstCommitWithoutPriorAdvertisement(GitProtocolVersion version) throws Exception {
        Path sourcePath = directory.resolve("source");
        try (OrionGitServer server = new OrionGitServer(HTTP);
             GitWorkTree source = GitClients.jgit().init(sourcePath);
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            source.writeFile("README.md", "without discovery\n");
            source.add("README.md");
            source.commit("initial");
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            OutputStreamBufferedByteOutput wire = new OutputStreamBufferedByteOutput(request);
            new GitPktLine.Data(("0".repeat(40) + " " + source.head() + " refs/heads/main\0report-status\n")
                    .getBytes(StandardCharsets.US_ASCII)).writeTo(wire);
            GitPktLine.Control.FLUSH.writeTo(wire);
            try (Git git = Git.open(sourcePath.toFile());
                 PackWriter pack = new PackWriter(git.getRepository())) {
                pack.preparePack(NullProgressMonitor.INSTANCE, Set.of(ObjectId.fromString(source.head())), Set.of());
                pack.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, request);
            }

            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                            URI.create(remote.uri() + "/git-receive-pack"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Git-Protocol", "version=" + version.wireValue())
                    .header("Content-Type", "application/x-git-receive-pack-request")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            List<String> statuses = new ArrayList<>();
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(response.body()))) {
                for (;;) {
                    GitPktLine packet = GitPktLine.readNextFrom(input).orElseThrow();
                    if (packet == GitPktLine.Control.FLUSH) {
                        break;
                    }
                    statuses.add(((GitPktLine.Data) packet).text());
                }
                assertThat(GitPktLine.readNextFrom(input)).isEmpty();
            }
            assertThat(statuses).containsExactly("unpack ok", "ok refs/heads/main");
            assertThat(source.snapshot().difference(server.snapshot(remote))).isNull();
        }
    }
}
