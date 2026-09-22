package pro.deta.orion.git.workflow.orion;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.core.CoreModuleProperties;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class FetchProtocolInteroperabilityTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        return Stream.of(Arguments.of("orion", GIT), Arguments.of("orion", HTTP),
                Arguments.of("orion", SSH), Arguments.of("git", GIT), Arguments.of("jgit", GIT));
    }

    @ParameterizedTest(name = "v2 empty repository discovery from {0} over {1}")
    @MethodSource("matrix")
    void advertisesV2AndListsNoRefsBeforeFirstPush(String engine, GitTransportScheme scheme) throws Exception {
        try (GitServer server = server(engine, scheme)) {
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            RepositorySnapshot before = server.snapshot(remote);
            assertThat(refs(remote, "")).isEmpty();
            assertThat(refs(remote, "HEAD")).isEmpty();
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }

    @ParameterizedTest(name = "v2 filtered refs from {0} over {1}")
    @MethodSource("matrix")
    void selectsHeadBranchesTagsAndMissingPrefix(String engine, GitTransportScheme scheme) throws Exception {
        try (GitServer server = server(engine, scheme);
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            GitRemoteRepository remote = seed(server, source);
            String commit = source.head();
            source.updateRef("refs/heads/feature", commit);
            source.updateRef("refs/tags/lightweight", commit);
            String tag = source.annotatedTag("annotated", commit);
            source.pushRefs("origin", "refs/heads/feature:refs/heads/feature",
                    "refs/tags/lightweight:refs/tags/lightweight", "refs/tags/annotated:refs/tags/annotated");
            RepositorySnapshot before = server.snapshot(remote);

            assertThat(refs(remote, "HEAD")).containsExactly(commit + " HEAD symref-target:refs/heads/main");
            assertThat(refs(remote, "refs/heads/")).containsExactlyInAnyOrder(
                    commit + " refs/heads/main", commit + " refs/heads/feature");
            assertThat(refs(remote, "refs/tags/")).containsExactlyInAnyOrder(
                    commit + " refs/tags/lightweight", tag + " refs/tags/annotated peeled:" + commit);
            assertThat(refs(remote, "refs/heads/absent")).isEmpty();
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }

    @ParameterizedTest(name = "v2 direct and already-common fetch from {0} over {1}")
    @MethodSource("matrix")
    void fetchesWithoutLsRefsAndSendsEmptyPackWhenWantIsAlreadyCommon(
            String engine, GitTransportScheme scheme) throws Exception {
        try (GitServer server = server(engine, scheme);
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            GitRemoteRepository remote = seed(server, source);
            RepositorySnapshot before = server.snapshot(remote);
            String want = "want " + source.head();
            try (IndexedPack pack = fetch(remote, List.of(want, "done"))) {
                RepositorySnapshot.Commit commit = before.commits().get(source.head());
                assertThat(pack.objectIds()).contains(new ObjectId(source.head()), new ObjectId(commit.tree()),
                        new ObjectId(commit.entries().get("README.md").objectId()));
            }
            try (IndexedPack pack = fetch(remote, List.of(want, "have " + source.head(), "done"))) {
                assertThat(pack.entryCount()).isZero();
            }
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }

    @ParameterizedTest(name = "v2 invalid fetch from {0} over {1}")
    @MethodSource("matrix")
    void rejectsZeroDepthAndMissingWantWithoutChangingRepository(
            String engine, GitTransportScheme scheme) throws Exception {
        try (GitServer server = server(engine, scheme);
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            GitRemoteRepository remote = seed(server, source);
            RepositorySnapshot before = server.snapshot(remote);
            for (List<String> arguments : List.of(
                    List.of("want " + source.head(), "deepen 0", "done"),
                    List.of("want " + "1".repeat(40), "done"))) {
                assertThatThrownBy(() -> {
                    try (IndexedPack ignored = fetch(remote, arguments)) {
                        // A completed pack would mean that the invalid request was accepted.
                    }
                }).isInstanceOf(IOException.class)
                        .isNotInstanceOf(SocketTimeoutException.class)
                        .isNotInstanceOf(HttpTimeoutException.class);
                assertThat(before.difference(server.snapshot(remote))).isNull();
                try (IndexedPack pack = fetch(remote, List.of("want " + source.head(), "done"))) {
                    assertThat(pack.objectIds()).contains(new ObjectId(source.head()));
                }
            }
        }
    }

    private GitRemoteRepository seed(GitServer server, GitWorkTree source) throws Exception {
        source.writeFile("README.md", "initial\n");
        source.add("README.md");
        source.commit("initial");
        GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
        source.addRemote("origin", remote);
        source.push("origin", "main");
        return remote;
    }

    private static GitServer server(String engine, GitTransportScheme scheme) throws IOException {
        return switch (engine) {
            case "orion" -> new OrionGitServer(scheme);
            case "git" -> GitServers.git();
            case "jgit" -> GitServers.jgit();
            default -> throw new IllegalArgumentException(engine);
        };
    }

    private static List<String> refs(GitRemoteRepository remote, String prefix) throws Exception {
        List<String> refs = new ArrayList<>();
        for (GitPktLine packet : exchange(remote, "ls-refs", List.of("symrefs", "peel", "ref-prefix " + prefix))) {
            refs.add(((GitPktLine.Data) packet).text());
        }
        return refs;
    }

    private static IndexedPack fetch(GitRemoteRepository remote, List<String> arguments) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean packfile = false;
        for (GitPktLine packet : exchange(remote, "fetch", arguments)) {
            if (!(packet instanceof GitPktLine.Data data)) {
                continue;
            }
            if (!packfile) {
                packfile = data.text().equals("packfile");
            } else if (data.content()[0] == 1) {
                bytes.write(data.content(), 1, data.content().length - 1);
            } else if (data.content()[0] != 2) {
                throw new IOException("Fetch returned a fatal sideband");
            }
        }
        if (!packfile) {
            throw new IOException("Fetch ended without a packfile section");
        }
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes.toByteArray()));
             PackIngestor ingestor = new PackIngestor(input, IndexedPack.create())) {
            return ingestor.ingest();
        }
    }

    private static List<GitPktLine> exchange(GitRemoteRepository remote, String command, List<String> arguments)
            throws Exception {
        URI uri = URI.create(remote.uri());
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput output = new OutputStreamBufferedByteOutput(request);
        data("command=" + command).writeTo(output);
        GitPktLine.Control.DELIMITER.writeTo(output);
        for (String argument : arguments) {
            data(argument).writeTo(output);
        }
        GitPktLine.Control.FLUSH.writeTo(output);
        byte[] body = request.toByteArray();
        switch (GitTransportScheme.from(uri)) {
            case GIT -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), (int) TIMEOUT.toMillis());
                    socket.setSoTimeout((int) TIMEOUT.toMillis());
                    OutputStreamBufferedByteOutput wire = new OutputStreamBufferedByteOutput(socket.getOutputStream());
                    new GitPktLine.Data(("git-upload-pack " + uri.getPath() + "\0host=" + uri.getHost()
                            + "\0\0version=2\0").getBytes(StandardCharsets.UTF_8)).writeTo(wire);
                    wire.flush();
                    return exchange(socket.getInputStream(), socket.getOutputStream(), body);
                }
            }
            case HTTP -> {
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
                    HttpResponse<byte[]> discovery = client.send(HttpRequest.newBuilder(
                                    URI.create(remote.uri() + "/info/refs?service=git-upload-pack"))
                            .timeout(TIMEOUT).header("Git-Protocol", "version=2").GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(discovery.statusCode()).isEqualTo(200);
                    try (BufferedByteInputV2 input = input(discovery.body())) {
                        advertisement(input);
                    }
                    HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                                    URI.create(remote.uri() + "/git-upload-pack"))
                            .timeout(TIMEOUT).header("Git-Protocol", "version=2")
                            .header("Content-Type", "application/x-git-upload-pack-request")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200) {
                        throw new IOException("Fetch HTTP response: " + response.statusCode());
                    }
                    try (BufferedByteInputV2 input = input(response.body())) {
                        return response(input);
                    }
                }
            }
            case SSH -> {
                try (SshClient client = SshClient.setUpDefaultClient()) {
                    client.setServerKeyVerifier((session, address, key) -> true);
                    CoreModuleProperties.IDLE_TIMEOUT.set(client, TIMEOUT);
                    client.start();
                    try (ClientSession session = client.connect(uri.getUserInfo(), uri.getHost(), uri.getPort())
                            .verify(TIMEOUT).getSession()) {
                        session.auth().verify(TIMEOUT);
                        try (ChannelExec channel = session.createExecChannel("git-upload-pack '" + uri.getPath() + "'")) {
                            channel.setEnv("GIT_PROTOCOL", "version=2");
                            channel.open().verify(TIMEOUT);
                            return exchange(channel.getInvertedOut(), channel.getInvertedIn(), body);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException(uri.toString());
        }
    }

    private static List<GitPktLine> exchange(InputStream source, OutputStream sink, byte[] request)
            throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(source)) {
            advertisement(input);
            sink.write(request);
            sink.flush();
            return response(input);
        }
    }

    private static void advertisement(BufferedByteInputV2 input) throws IOException {
        List<GitPktLine> advertised = response(input);
        assertThat(((GitPktLine.Data) advertised.getFirst()).text()).isEqualTo("version 2");
        List<String> capabilities = new ArrayList<>();
        for (GitPktLine packet : advertised.subList(1, advertised.size())) {
            capabilities.add(((GitPktLine.Data) packet).text());
        }
        for (String command : List.of("ls-refs", "fetch")) {
            assertThat(capabilities).anyMatch(value -> value.equals(command) || value.startsWith(command + "="));
        }
    }

    private static List<GitPktLine> response(BufferedByteInputV2 input) throws IOException {
        List<GitPktLine> packets = new ArrayList<>();
        for (;;) {
            GitPktLine packet = GitPktLine.readNextFrom(input)
                    .orElseThrow(() -> new EOFException("Server ended the response before FLUSH"));
            if (packet == GitPktLine.Control.FLUSH) {
                return packets;
            }
            if (packet instanceof GitPktLine.Data data && data.content().length >= 4
                    && new String(data.content(), 0, 4, StandardCharsets.US_ASCII).equals("ERR ")) {
                throw new IOException(data.text());
            }
            packets.add(packet);
        }
    }

    private static GitPktLine.Data data(String value) {
        return new GitPktLine.Data((value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static BufferedByteInputV2 input(byte[] bytes) {
        return new BufferedByteInputV2(new ByteArrayInputStream(bytes));
    }
}
