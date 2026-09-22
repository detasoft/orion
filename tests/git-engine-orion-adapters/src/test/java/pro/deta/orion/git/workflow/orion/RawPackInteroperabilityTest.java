package pro.deta.orion.git.workflow.orion;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitSmartHttpClientTransport;
import pro.deta.orion.git.client.GitSshClientTransport;
import pro.deta.orion.git.client.GitTcpClientTransport;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.client.GitUploadPackResult;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class RawPackInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        return Stream.of(Arguments.of("orion", GIT), Arguments.of("orion", HTTP),
                Arguments.of("orion", SSH), Arguments.of("git", GIT), Arguments.of("jgit", GIT));
    }

    @ParameterizedTest(name = "Orion raw fetch from {0} over {1}")
    @MethodSource("matrix")
    void incrementallyFetchesRawPackAfterDetailedAcknowledgments(String engine, GitTransportScheme scheme)
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
            GitUploadPackClient client = new GitUploadPackClient(withoutSideBand(transport));
            source.writeFile("README.md", "initial\n");
            source.add("README.md");
            source.commit("initial");
            String common = source.head();
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(directory.resolve("copy"));
            try (NativeGitRepository copy = provider.create("project").valueOrFailure("repository")) {
                fetch(client, remote, common, List.of(), copy);
                source.writeFile("README.md", "updated\n");
                source.add("README.md");
                source.commit("second");
                source.push("origin", "main");
                fetch(client, remote, source.head(), List.of(common), copy);
                copy.updateRef("refs/heads/main", "0".repeat(40), source.head());
                assertThat(new String(copy.loadFiles("main", List.of("README.md")).files().get("README.md"),
                        StandardCharsets.UTF_8)).isEqualTo("updated\n");
            }
        }
    }

    private static void fetch(GitUploadPackClient client, GitRemoteRepository remote, String want,
            List<String> haves, NativeGitRepository copy) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitUploadPackResult result = OrionGitClient.requireSuccess(client.fetch(URI.create(remote.uri()),
                GitClientOptions.defaults(), new GitUploadPackRequest(List.of(want), haves,
                        new OutputStreamBufferedByteOutput(bytes), ignored -> { })), "raw fetch");
        assertThat(result.advertisement().capabilities()).contains("multi_ack_detailed")
                .doesNotContain("side-band", "side-band-64k");
        assertThat(result.packBytes()).isEqualTo(bytes.size());
        assertThat(bytes.toByteArray()).startsWith("PACK".getBytes(StandardCharsets.US_ASCII));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes.toByteArray()));
             IndexedPack pack = copy.ingest(input)) {
            copy.storage().persist(pack);
        }
    }

    private static GitClientTransport withoutSideBand(GitClientTransport transport) {
        return (service, uri, options) -> {
            GitClientTransportSession delegate = transport.open(service, uri, options);
            BufferedByteInputV2 input = new BufferedByteInputV2(new BufferedByteInputV2.Source() {
                private boolean advertised;

                @Override
                public ByteBuffer read() throws IOException {
                    if (advertised) {
                        return delegate.input().buffer();
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    OutputStreamBufferedByteOutput output = new OutputStreamBufferedByteOutput(bytes);
                    for (;;) {
                        GitPktLine packet = GitPktLine.readNextFrom(delegate.input()).orElseThrow();
                        if (packet instanceof GitPktLine.Data data) {
                            String line = new String(data.content(), StandardCharsets.UTF_8);
                            int separator = line.indexOf('\0');
                            if (separator >= 0) {
                                List<String> capabilities = new ArrayList<>();
                                for (String token : line.substring(separator + 1).strip().split(" ")) {
                                    if (!token.equals("side-band") && !token.equals("side-band-64k")) {
                                        capabilities.add(token);
                                    }
                                }
                                packet = new GitPktLine.Data((line.substring(0, separator + 1)
                                        + String.join(" ", capabilities) + "\n").getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        packet.writeTo(output);
                        if (packet == GitPktLine.Control.FLUSH) {
                            break;
                        }
                    }
                    advertised = true;
                    return ByteBuffer.wrap(bytes.toByteArray());
                }

                @Override
                public void release() {}

                @Override
                public void close() {}
            });
            return new GitClientTransportSession() {
                @Override
                public BufferedByteInputV2 input() { return input; }

                @Override
                public BufferedByteOutput output() { return delegate.output(); }

                @Override
                public void close() throws IOException {
                    try {
                        input.close();
                    } finally {
                        delegate.close();
                    }
                }
            };
        };
    }
}
