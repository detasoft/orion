package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.GitInitialServiceRequest;
import pro.deta.orion.git.parser.wire.GitInitialServiceRequestParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NativeGitCloneCompatibilityTest {
    private static final String NULL_ID = "0".repeat(40);

    @TempDir
    private Path tempDir;

    @Test
    void gitCliCanCloneProtocolV2Repository() throws Exception {
        TestRepository fixture = TestRepository.withReadme();
        Path cloneDir = tempDir.resolve("clone");

        try (NativeGitServer server = NativeGitServer.start(fixture.repository())) {
            ProcessResult result = runGit(
                    "git",
                    "clone",
                    "--config",
                    "protocol.version=2",
                    server.url(),
                    cloneDir.toString());

            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(Files.readString(cloneDir.resolve("README.md"))).isEqualTo("hello from native\n");
        }
    }

    @Test
    void gitCliCanCloneEmptyProtocolV2Repository() throws Exception {
        TestRepository fixture = TestRepository.empty();
        Path cloneDir = tempDir.resolve("empty-clone");

        try (NativeGitServer server = NativeGitServer.start(fixture.repository())) {
            ProcessResult result = runGit(
                    "git",
                    "clone",
                    "--config",
                    "protocol.version=2",
                    server.url(),
                    cloneDir.toString());

            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(Files.exists(cloneDir.resolve(".git"))).isTrue();
        }
    }

    private ProcessResult runGit(String... command) throws Exception {
        Path outputFile = Files.createTempFile(tempDir, "git-output-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.readString(outputFile);
        assertThat(finished).as(output).isTrue();
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record TestRepository(NativeGitRepository repository, LooseRefStore refs, LooseObjectStore objects) {
        static TestRepository withReadme() {
            LooseRefStore refs = new LooseRefStore();
            LooseObjectStore objects = new LooseObjectStore();
            GitObjectId blob = objects.write(ObjectType.BLOB, "hello from native\n".getBytes(StandardCharsets.UTF_8));
            GitObjectId tree = objects.write(ObjectType.TREE, tree("README.md", blob));
            String commit = """
                    tree %s
                    author Native Test <native@example.test> 0 +0000
                    committer Native Test <native@example.test> 0 +0000

                    initial
                    """.formatted(tree.value());
            GitObjectId commitId = objects.write(ObjectType.COMMIT, commit.getBytes(StandardCharsets.UTF_8));
            refs.update("refs/heads/main", NULL_ID, commitId.value());
            return new TestRepository(new NativeGitRepository(
                    "project.git",
                    "Native compatibility fixture",
                    refs,
                    objects,
                    Optional.of("refs/heads/main")),
                    refs,
                    objects);
        }

        static TestRepository empty() {
            LooseRefStore refs = new LooseRefStore();
            LooseObjectStore objects = new LooseObjectStore();
            return new TestRepository(new NativeGitRepository(
                    "empty.git",
                    "Empty native compatibility fixture",
                    refs,
                    objects,
                    Optional.of("refs/heads/main")),
                    refs,
                    objects);
        }

        private static byte[] tree(String name, GitObjectId blob) {
            byte[] prefix = ("100644 " + name + "\0").getBytes(StandardCharsets.UTF_8);
            byte[] id = HexFormat.of().parseHex(blob.value());
            byte[] result = new byte[prefix.length + id.length];
            System.arraycopy(prefix, 0, result, 0, prefix.length);
            System.arraycopy(id, 0, result, prefix.length, id.length);
            return result;
        }
    }

    private static final class NativeGitServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final CompletableFuture<Void> server;

        private NativeGitServer(
                ServerSocket serverSocket,
                NativeGitRepository repository) {
            this.serverSocket = serverSocket;
            this.server = CompletableFuture.runAsync(() -> serveOneClient(serverSocket, repository));
        }

        static NativeGitServer start(NativeGitRepository repository) throws IOException {
            ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            return new NativeGitServer(socket, repository);
        }

        String url() {
            return "git://" + serverSocket.getInetAddress().getHostAddress()
                    + ":" + serverSocket.getLocalPort()
                    + "/project.git";
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            server.get(10, TimeUnit.SECONDS);
        }

        private static void serveOneClient(
                ServerSocket serverSocket,
                NativeGitRepository repository) {
            try (Socket socket = serverSocket.accept()) {
                GitInitialServiceRequest request = readInitialRequest(socket.getInputStream());
                if (request.service() == GitInitialServiceRequest.Service.UPLOAD_PACK) {
                    repository.upload(
                            new GitUploadRequest(0, extraParameters(request), _stats -> {
                            }),
                            socket.getInputStream(),
                            socket.getOutputStream(),
                            socket.getOutputStream());
                } else {
                    throw new IllegalStateException("Unexpected Git service: " + request.service());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static GitInitialServiceRequest readInitialRequest(InputStream input) throws IOException {
            byte[] header = input.readNBytes(4);
            if (header.length != 4) {
                throw new EOFException("Truncated initial Git service request header");
            }
            int length = parseLength(header);
            byte[] payload = input.readNBytes(length - 4);
            if (payload.length != length - 4) {
                throw new EOFException("Truncated initial Git service request payload");
            }
            ByteBuf buffer = Unpooled.buffer(length, length);
            try {
                buffer.writeBytes(header);
                buffer.writeBytes(payload);
                return GitInitialServiceRequestParser.read(buffer);
            } finally {
                buffer.release();
            }
        }

        private static int parseLength(byte[] header) {
            int value = 0;
            for (byte b : header) {
                value = (value << 4) | Character.digit((char) b, 16);
            }
            return value;
        }

        private static Set<String> extraParameters(GitInitialServiceRequest request) {
            Set<String> result = new LinkedHashSet<>();
            request.parameters().forEach((key, value) -> {
                if (value.isEmpty()) {
                    result.add(key);
                } else {
                    result.add(key + "=" + value);
                }
            });
            return result;
        }
    }
}
