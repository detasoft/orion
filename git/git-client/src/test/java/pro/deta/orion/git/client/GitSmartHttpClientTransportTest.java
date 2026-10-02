package pro.deta.orion.git.client;

import java.util.Set;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.schema.orion.v2.GitCredentialKind;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.Reference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitSmartHttpClientTransportTest {
    private static final String OLD_ID =
            "1111111111111111111111111111111111111111";
    private static final String NEW_ID =
            "2222222222222222222222222222222222222222";

    @Test
    void clearsBasicAuthenticationBytesAfterEncodingTheRemainingBuffer() {
        byte[] bytes = "skipuser:password".getBytes(StandardCharsets.UTF_8);
        ByteBuffer credentials = ByteBuffer.wrap(bytes);
        credentials.position(4);

        assertThat(GitSmartHttpClientTransport.basicAuthorization(credentials))
                .isEqualTo("Basic dXNlcjpwYXNzd29yZA==");
        assertThat(bytes).containsOnly((byte) 0);
    }

    @Test
    void fetchesAndPushesAgainstCanonicalGitHttpBackend(
            @TempDir Path temporaryDirectory) throws Exception {
        TestRepository repository = createRepository(temporaryDirectory);
        try (GitHttpBackendTestServer server = GitHttpBackendTestServer.start(
                temporaryDirectory, repository.path())) {
            GitSmartHttpClientTransport transport = transport(true);

            GitClientResult<GitUploadPackResult<Long>> fetch =
                    new GitUploadPackClient(transport).fetch(
                            server.repositoryUri(),
                            GitClientOptions.defaults(),
                            GitUploadPackRequest.of(
                                    repository.commitId(),
                                    input -> {
                                        assertThat(input.readInt()).isEqualTo(0x5041434b);
                                        return input.newInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                                    }));

            assertThat(fetch).isInstanceOf(GitClientResult.Success.class);

            GitReceivePackRequest delete = new GitReceivePackRequest(
                    List.of(new GitReceivePackRequest.Command(
                            repository.commitId(),
                            GitClientValidation.NULL_ID,
                            "refs/heads/delete-me")),
                    output -> { });
            GitClientResult<GitReceivePackResult> push =
                    new GitReceivePackClient(transport).push(
                            server.repositoryUri(),
                            GitClientOptions.defaults(),
                            delete);

            assertThat(push).isInstanceOf(GitClientResult.Success.class);
            assertThat(success(push).accepted()).isTrue();
        }

        try (Repository checked = new FileRepositoryBuilder()
                .setGitDir(repository.path().toFile())
                .build()) {
            assertThat(checked.exactRef("refs/heads/delete-me")).isNull();
        }
    }

    @Test
    void preservesRequestAndResponseStreamingThroughSmartHttp() throws Exception {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        AtomicReference<byte[]> uploadRequest = new AtomicReference<>();
        AtomicReference<byte[]> receiveRequest = new AtomicReference<>();
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            authorizations.add(exchange.getRequestMethod() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            String service = service(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] advertisement = advertisement(service);
                respond(exchange, 200,
                        "application/x-" + service + "-advertisement",
                        concat(packet("# service=" + service + "\n"),
                                flush(), advertisement));
                return;
            }
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (GitClientService.UPLOAD_PACK.command().equals(service)) {
                uploadRequest.set(request);
                byte[] pack = "PACKsmart-http".getBytes(StandardCharsets.US_ASCII);
                respond(exchange, 200,
                        "application/x-git-upload-pack-result",
                        concat(packet("NAK\n"), sideBandPacket(1, pack), flush()));
            } else {
                receiveRequest.set(request);
                respond(exchange, 200,
                        "application/x-git-receive-pack-result",
                        concat(packet("unpack ok\n"),
                                packet("ok refs/heads/main\n"), flush()));
            }
        })) {
            GitClientTransport transport = new GitRemoteClientTransport(null,
                    new GitCredentials(GitCredentialKind.TOKEN, "", "secret".toCharArray()), Set.of(), true);
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            GitClientResult<GitUploadPackResult<Long>> fetch =
                    new GitUploadPackClient(transport).fetch(
                            server.repositoryUri(),
                            GitClientOptions.defaults(),
                            GitUploadPackRequest.of(
                                    OLD_ID,
                                    input -> input.newInputStream().transferTo(pack)));

            assertThat(fetch).isInstanceOf(GitClientResult.Success.class);
            assertThat(pack.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("PACKsmart-http");
            assertThat(new String(uploadRequest.get(), StandardCharsets.UTF_8))
                    .contains("want " + OLD_ID)
                    .contains("done\n");

            GitReceivePackRequest pushRequest = new GitReceivePackRequest(
                    List.of(new GitReceivePackRequest.Command(
                            OLD_ID, NEW_ID, "refs/heads/main")),
                    output -> output.write("PACKpush".getBytes(
                            StandardCharsets.US_ASCII)));
            GitClientResult<GitReceivePackResult> push =
                    new GitReceivePackClient(transport).push(
                            server.repositoryUri(),
                            GitClientOptions.defaults(),
                            pushRequest);

            assertThat(push).isInstanceOf(GitClientResult.Success.class);
            assertThat(success(push).accepted()).isTrue();
            assertThat(receiveRequest.get()).endsWith(
                    "PACKpush".getBytes(StandardCharsets.US_ASCII));
            assertThat(authorizations).containsExactly(
                    "GET Bearer secret", "POST Bearer secret", "GET Bearer secret", "POST Bearer secret");
        }
    }

    @Test
    void doesNotStartPostDuringDiscovery() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            String service = service(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200,
                        "application/x-" + service + "-advertisement",
                        concat(packet("# service=" + service + "\n"),
                                flush(), advertisement(service)));
                return;
            }
            posts.incrementAndGet();
            respond(exchange, 500, "text/plain", new byte[0]);
        })) {
            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport(true)).discover(
                            server.repositoryUri(), GitClientOptions.defaults());

            assertThat(result).isInstanceOf(GitClientResult.Success.class);
            assertThat(posts).hasValue(0);
        }
    }

    @Test
    void configuresAuthenticationWithoutPuttingItInUri() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String service = service(exchange);
            respond(exchange, 200,
                    "application/x-" + service + "-advertisement",
                    concat(packet("# service=" + service + "\n"),
                            flush(), advertisement(service)));
        })) {
            GitSmartHttpClientTransport transport = new GitSmartHttpClientTransport(
                    HttpClient.newBuilder()
                            .connectTimeout(GitClientOptions.defaults().connectTimeout())
                            .build(),
                    new GitCredentials(GitCredentialKind.TOKEN, "", "secret".toCharArray()),
                    true);

            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport).discover(
                            server.repositoryUri(), GitClientOptions.defaults());

            assertThat(result).isInstanceOf(GitClientResult.Success.class);
            assertThat(authorization.get()).isEqualTo("Bearer secret");
        }
    }

    @Test
    void mapsDiscoveryAuthenticationFailure() throws Exception {
        try (TestHttpServer server = TestHttpServer.start(exchange ->
                respond(exchange, 401, "text/plain",
                        "denied".getBytes(StandardCharsets.UTF_8)))) {
            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport(true)).discover(
                            server.repositoryUri(), GitClientOptions.defaults());

            assertThat(failure(result).kind())
                    .isEqualTo(GitClientFailure.Kind.AUTHENTICATION_FAILED);
            assertThat(failure(result).retryable()).isFalse();
        }
    }

    @Test
    void rejectsRedirectInsteadOfFollowingIt() throws Exception {
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            exchange.getResponseHeaders().set("Location", "/other.git");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        })) {
            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport(true)).discover(
                            server.repositoryUri(), GitClientOptions.defaults());

            assertThat(failure(result).kind())
                    .isEqualTo(GitClientFailure.Kind.SERVER_ERROR);
        }
    }

    @Test
    void timesOutStalledDiscoveryBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            exchange.getResponseHeaders().set("Content-Type",
                    "application/x-git-upload-pack-advertisement");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Stalled discovery was interrupted", error);
            } finally {
                exchange.close();
            }
        })) {
            Duration timeout = Duration.ofMillis(50);
            GitClientOptions options = new GitClientOptions(
                    GitClientOptions.defaults().connectTimeout(), timeout, timeout,
                    GitClientOptions.defaults().operationTimeout(), 1);
            long started = System.nanoTime();
            GitClientResult<GitRemoteAdvertisement> result;
            try {
                result = new GitUploadPackClient(transport(true)).discover(
                        server.repositoryUri(), options);

                assertThat(failure(result).kind()).isEqualTo(GitClientFailure.Kind.TIMEOUT);
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(Duration.ofSeconds(1));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void permitsDiscoveryBodyThatMakesProgressBeforeEachReadTimeout()
            throws Exception {
        Duration timeout = Duration.ofMillis(100);
        byte[] body = concat(packet("# service=git-upload-pack\n"), flush(),
                advertisement(GitClientService.UPLOAD_PACK.command()));
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            exchange.getResponseHeaders().set("Content-Type",
                    "application/x-git-upload-pack-advertisement");
            exchange.sendResponseHeaders(200, 0);
            writeInChunks(exchange, body, 3, Duration.ofMillis(60));
            exchange.close();
        })) {
            GitClientOptions options = new GitClientOptions(
                    GitClientOptions.defaults().connectTimeout(), timeout, timeout,
                    GitClientOptions.defaults().operationTimeout(), 1);
            long started = System.nanoTime();
            GitClientResult<GitRemoteAdvertisement> result =
                    new GitUploadPackClient(transport(true)).discover(
                            server.repositoryUri(), options);

            assertThat(result).isInstanceOf(GitClientResult.Success.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isGreaterThan(timeout);
        }
    }

    @Test
    void rejectsInjectedClientWithoutConnectTimeout() {
        GitSmartHttpClientTransport transport = new GitSmartHttpClientTransport(
                HttpClient.newHttpClient(), GitCredentials.none(), true);

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(
                        URI.create("http://127.0.0.1/repository.git"),
                        GitClientOptions.defaults());

        assertThat(failure(result).kind()).isEqualTo(
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED);
    }

    @Test
    void defaultClientRejectsRedirects() {
        assertThat(GitSmartHttpClientTransport.defaultClient(
                Duration.ofSeconds(1)).followRedirects())
                .isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void closesOwnedClientConnectionWhenDiscoverySessionCloses() throws Exception {
        byte[] body = concat(packet("# service=git-upload-pack\n"), flush(),
                advertisement(GitClientService.UPLOAD_PACK.command()));
        try (KeepAlivePeer peer = new KeepAlivePeer(body)) {
            GitSmartHttpClientTransport transport = new GitSmartHttpClientTransport(
                    null, GitCredentials.none(), true);
            GitClientTransportSession session = transport.open(
                    GitClientService.UPLOAD_PACK, peer.repositoryUri(), GitClientOptions.defaults());
            try (session) {
                session.close();
                session.close();

                peer.awaitClientClose();
            } finally {
                Reference.reachabilityFence(session);
            }
        }
    }

    @Test
    void closesOwnedClientConnectionAfterMalformedDiscovery() throws Exception {
        try (KeepAlivePeer peer = new KeepAlivePeer(
                "invalid advertisement".getBytes(StandardCharsets.US_ASCII))) {
            GitSmartHttpClientTransport transport = new GitSmartHttpClientTransport(
                    null, GitCredentials.none(), true);

            assertThatThrownBy(() -> transport.open(
                    GitClientService.UPLOAD_PACK, peer.repositoryUri(), GitClientOptions.defaults()))
                    .isInstanceOf(GitClientTransportException.class)
                    .hasMessage("Malformed Git Smart HTTP service advertisement");

            peer.awaitClientClose();
        }
    }

    @Test
    void timesOutStalledPostWithAnOwnedClient() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();
        try (TestHttpServer server = TestHttpServer.start(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "application/x-git-upload-pack-advertisement",
                        concat(packet("# service=git-upload-pack\n"), flush(),
                                advertisement(GitClientService.UPLOAD_PACK.command())));
                return;
            }
            exchange.getRequestBody().readAllBytes();
            posts.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-result");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(packet("NAK\n"));
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Stalled POST was interrupted", error);
            } finally {
                exchange.close();
            }
        })) {
            GitSmartHttpClientTransport transport = new GitSmartHttpClientTransport(
                    null, GitCredentials.none(), true);
            GitClientOptions options = new GitClientOptions(
                    GitClientOptions.defaults().connectTimeout(), Duration.ofMillis(300),
                    Duration.ofSeconds(2), Duration.ofSeconds(10), 1);
            long started = System.nanoTime();
            try {
                GitClientResult<GitUploadPackResult<Long>> result = new GitUploadPackClient(transport).fetch(
                        server.repositoryUri(), options,
                        GitUploadPackRequest.of(OLD_ID,
                                input -> input.newInputStream().transferTo(java.io.OutputStream.nullOutputStream())));

                assertThat(failure(result).kind()).isEqualTo(GitClientFailure.Kind.TIMEOUT);
                assertThat(posts).hasValue(1);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void keepsInjectedClientUsableAfterFailedOpenAndSessionClose() throws Exception {
        byte[] body = concat(packet("# service=git-upload-pack\n"), flush(),
                advertisement(GitClientService.UPLOAD_PACK.command()));
        try (KeepAlivePeer peer = new KeepAlivePeer(
                "invalid advertisement".getBytes(StandardCharsets.US_ASCII), body, body);
             HttpClient client = HttpClient.newBuilder()
                     .connectTimeout(GitClientOptions.defaults().connectTimeout()).build()) {
            GitSmartHttpClientTransport transport = new GitSmartHttpClientTransport(
                    client, GitCredentials.none(), true);
            assertThatThrownBy(() -> transport.open(
                    GitClientService.UPLOAD_PACK, peer.repositoryUri(), GitClientOptions.defaults()))
                    .isInstanceOf(GitClientTransportException.class);

            for (int attempt = 0; attempt < 2; attempt++) {
                try (GitClientTransportSession session = transport.open(
                        GitClientService.UPLOAD_PACK, peer.repositoryUri(), GitClientOptions.defaults())) {
                    assertThat(session.input()).isNotNull();
                }
            }

            assertThat(client.isTerminated()).isFalse();
            assertThat(peer.requests).hasValue(3);
        }
    }

    private static final class KeepAlivePeer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch clientClosed = new CountDownLatch(1);
        private volatile Socket connection;

        private KeepAlivePeer(byte[]... bodies) throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            server = Thread.ofPlatform().start(() -> {
                try (Socket socket = listener.accept()) {
                    connection = socket;
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request;
                    while ((request = input.readLine()) != null) {
                        assertThat(request).startsWith("GET /repository.git/info/refs?");
                        String header;
                        while ((header = input.readLine()) != null && !header.isEmpty()) {
                            // Consume the request headers before responding on the same connection.
                        }
                        byte[] body = bodies[requests.getAndIncrement()];
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/x-git-upload-pack-advertisement\r\n"
                                + "Content-Length: " + body.length + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    clientClosed.countDown();
                }
            });
        }

        private URI repositoryUri() {
            return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/repository.git");
        }

        private void awaitClientClose() throws InterruptedException {
            assertThat(clientClosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            Socket socket = connection;
            if (socket != null) {
                socket.close();
            }
            server.join(Duration.ofSeconds(5));
            assertThat(server.isAlive()).isFalse();
        }
    }

    private static GitSmartHttpClientTransport transport(boolean allowHttp) {
        return new GitSmartHttpClientTransport(
                HttpClient.newBuilder()
                        .connectTimeout(GitClientOptions.defaults().connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                GitCredentials.none(),
                allowHttp);
    }

    private static TestRepository createRepository(Path temporaryDirectory)
            throws Exception {
        Path seedDirectory = temporaryDirectory.resolve("seed");
        ObjectId commitId;
        try (Git seed = Git.init()
                .setDirectory(seedDirectory.toFile())
                .setInitialBranch("main")
                .call()) {
            Files.writeString(seedDirectory.resolve("README.md"), "HTTP fixture\n");
            seed.add().addFilepattern("README.md").call();
            commitId = seed.commit()
                    .setMessage("Seed repository")
                    .setAuthor("Orion Test", "orion@example.invalid")
                    .setCommitter("Orion Test", "orion@example.invalid")
                    .call();
        }
        Path bareRepository = temporaryDirectory.resolve("repository.git");
        try (Git remote = Git.cloneRepository()
                .setURI(seedDirectory.toUri().toString())
                .setDirectory(bareRepository.toFile())
                .setBare(true)
                .call()) {
            RefUpdate branch = remote.getRepository()
                    .updateRef("refs/heads/delete-me");
            branch.setNewObjectId(commitId);
            assertThat(branch.update()).isEqualTo(RefUpdate.Result.NEW);
            remote.getRepository().getConfig().setBoolean(
                    "http", null, "receivepack", true);
            remote.getRepository().getConfig().save();
        }
        return new TestRepository(bareRepository, commitId.name());
    }

    private static String service(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/git-receive-pack")
                || exchange.getRequestURI().getRawQuery() != null
                && exchange.getRequestURI().getRawQuery().contains("git-receive-pack")) {
            return GitClientService.RECEIVE_PACK.command();
        }
        return GitClientService.UPLOAD_PACK.command();
    }

    private static byte[] advertisement(String service) {
        String capabilities = GitClientService.UPLOAD_PACK.command().equals(service)
                ? "side-band-64k multi_ack_detailed"
                : "report-status";
        return concat(packet(OLD_ID + " refs/heads/main\0"
                + capabilities + "\n"), flush());
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void writeInChunks(
            HttpExchange exchange,
            byte[] body,
            int chunks,
            Duration pause) throws IOException {
        int offset = 0;
        for (int chunk = 0; chunk < chunks; chunk++) {
            int remainingChunks = chunks - chunk;
            int length = (body.length - offset + remainingChunks - 1) / remainingChunks;
            exchange.getResponseBody().write(body, offset, length);
            exchange.getResponseBody().flush();
            offset += length;
            if (chunk + 1 < chunks) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Chunked discovery was interrupted", error);
                }
            }
        }
    }

    private static byte[] sideBandPacket(int channel, byte[] data) {
        byte[] payload = new byte[data.length + 1];
        payload[0] = (byte) channel;
        System.arraycopy(data, 0, payload, 1, data.length);
        return packet(payload);
    }

    private static byte[] packet(String payload) {
        return packet(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] packet(byte[] payload) {
        return concat("%04x".formatted(payload.length + 4)
                .getBytes(StandardCharsets.US_ASCII), payload);
    }

    private static byte[] flush() {
        return "0000".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            result.writeBytes(chunk);
        }
        return result.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(GitClientResult<T> result) {
        return ((GitClientResult.Success<T>) result).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> GitClientFailure failure(GitClientResult<T> result) {
        assertThat(result).isInstanceOf(GitClientResult.Failed.class);
        return ((GitClientResult.Failed<T>) result).failure();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record TestRepository(Path path, String commitId) {
    }

    private static final class GitHttpBackendTestServer implements AutoCloseable {
        private final Path projectRoot;
        private final HttpServer server;

        private GitHttpBackendTestServer(Path projectRoot, HttpServer server) {
            this.projectRoot = projectRoot;
            this.server = server;
        }

        private static GitHttpBackendTestServer start(
                Path projectRoot,
                Path repository) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), 0), 0);
            GitHttpBackendTestServer result = new GitHttpBackendTestServer(
                    projectRoot, server);
            server.createContext("/repository.git", result::handle);
            server.start();
            return result;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] request = exchange.getRequestBody().readAllBytes();
            ProcessBuilder command = new ProcessBuilder("git", "http-backend");
            Map<String, String> environment = command.environment();
            environment.put("GIT_PROJECT_ROOT", projectRoot.toString());
            environment.put("GIT_HTTP_EXPORT_ALL", "1");
            environment.put("PATH_INFO", exchange.getRequestURI().getPath());
            environment.put("REQUEST_METHOD", exchange.getRequestMethod());
            environment.put("QUERY_STRING", query(exchange));
            environment.put("CONTENT_TYPE", exchange.getRequestHeaders()
                    .getFirst("Content-Type") == null ? "" : exchange
                    .getRequestHeaders().getFirst("Content-Type"));
            environment.put("CONTENT_LENGTH", Integer.toString(request.length));
            Process process = command.start();
            try (var output = process.getOutputStream()) {
                output.write(request);
            }
            byte[] response = process.getInputStream().readAllBytes();
            String error = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            try {
                if (process.waitFor() != 0) {
                    throw new IOException("git http-backend failed: " + error);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("git http-backend was interrupted", interrupted);
            }
            respondCgi(exchange, response);
        }

        private static String query(HttpExchange exchange) {
            String query = exchange.getRequestURI().getRawQuery();
            return query == null ? "" : query;
        }

        private static void respondCgi(HttpExchange exchange, byte[] response)
                throws IOException {
            int separator = indexOf(response, "\r\n\r\n".getBytes(
                    StandardCharsets.US_ASCII));
            if (separator < 0) {
                throw new IOException("git http-backend returned malformed CGI response");
            }
            String headers = new String(response, 0, separator,
                    StandardCharsets.ISO_8859_1);
            int status = 200;
            for (String header : headers.split("\r\n")) {
                int colon = header.indexOf(':');
                if (colon < 1) {
                    continue;
                }
                String name = header.substring(0, colon);
                String value = header.substring(colon + 1).trim();
                if ("Status".equalsIgnoreCase(name)) {
                    status = Integer.parseInt(value.substring(0, 3));
                } else {
                    exchange.getResponseHeaders().add(name, value);
                }
            }
            byte[] body = java.util.Arrays.copyOfRange(
                    response, separator + 4, response.length);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private static int indexOf(byte[] value, byte[] target) {
            for (int offset = 0; offset <= value.length - target.length; offset++) {
                int index = 0;
                while (index < target.length && value[offset + index] == target[index]) {
                    index++;
                }
                if (index == target.length) {
                    return offset;
                }
            }
            return -1;
        }

        private URI repositoryUri() {
            return URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/repository.git");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpServer(HttpServer server) {
            this.server = server;
        }

        private static TestHttpServer start(Handler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/repository.git", exchange -> handler.handle(exchange));
            server.start();
            return new TestHttpServer(server);
        }

        private URI repositoryUri() {
            return URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/repository.git");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
