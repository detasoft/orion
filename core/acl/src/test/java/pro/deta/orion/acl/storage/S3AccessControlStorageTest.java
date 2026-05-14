package pro.deta.orion.acl.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3AccessControlStorageTest {
    private static final String ACL_FILE = "orion.xml";
    private static final String EXTRA_FILE = "roles.xml";

    @Test
    void loadsAllConfiguredFilesFromS3Prefix() {
        RecordingS3Client client = new RecordingS3Client();
        client.put("acl-bucket", "bootstrap/orion.xml", "acl".getBytes(StandardCharsets.UTF_8));
        client.put("acl-bucket", "bootstrap/roles.xml", "roles".getBytes(StandardCharsets.UTF_8));
        S3AccessControlStorage storage = new S3AccessControlStorage(
                s3Config("s3://acl-bucket/bootstrap", List.of(ACL_FILE, EXTRA_FILE)),
                client);

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load from S3");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_FILE, EXTRA_FILE);
        assertThat(snapshot.files().get(ACL_FILE)).containsExactly("acl".getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.files().get(EXTRA_FILE)).containsExactly("roles".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void missingConfiguredFileReturnsNotFound() {
        RecordingS3Client client = new RecordingS3Client();
        client.put("acl-bucket", "bootstrap/roles.xml", "roles".getBytes(StandardCharsets.UTF_8));
        S3AccessControlStorage storage = new S3AccessControlStorage(
                s3Config("s3://acl-bucket/bootstrap", List.of(ACL_FILE)),
                client);

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        Result.Failure<?> failure = (Result.Failure<?>) result;
        assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_FOUND);
    }

    @Test
    void savesAllSnapshotFilesToS3Prefix() {
        RecordingS3Client client = new RecordingS3Client();
        S3AccessControlStorage storage = new S3AccessControlStorage(
                s3Config("s3://acl-bucket/bootstrap", List.of(ACL_FILE, EXTRA_FILE)),
                client);

        storage.save(
                new AccessControlSnapshot(
                        Map.of(
                                ACL_FILE, "saved-acl".getBytes(StandardCharsets.UTF_8),
                                EXTRA_FILE, "saved-roles".getBytes(StandardCharsets.UTF_8)),
                        Optional.empty()),
                new AccessControlSaveRequest("save S3 ACL", new UserEmail("tester", "tester@example.test")));

        assertThat(client.get("acl-bucket", "bootstrap/orion.xml"))
                .containsExactly("saved-acl".getBytes(StandardCharsets.UTF_8));
        assertThat(client.get("acl-bucket", "bootstrap/roles.xml"))
                .containsExactly("saved-roles".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loadsAndSavesThroughFakeS3Endpoint(@TempDir Path tempDir) throws Exception {
        Path secretFile = tempDir.resolve("s3-secret.txt");
        Files.writeString(secretFile, "fake-s3-secret");

        try (FakeS3Server s3 = FakeS3Server.start()) {
            s3.putObject("acl-bucket", "bootstrap/orion.xml", "acl".getBytes(StandardCharsets.UTF_8));
            OrionConfiguration.BootstrapAccessControlConfig config = s3Config(
                    "s3://acl-bucket/bootstrap",
                    List.of(ACL_FILE));
            config.getAuth().put("endpoint", s3.endpoint());
            config.getAuth().put("region", "us-east-1");
            config.getAuth().put("accessKeyId", "fake-s3-access");
            config.getAuth().put("secretAccessKey", secretFile.toUri().toString());
            S3AccessControlStorage storage = new S3AccessControlStorage(config);

            AccessControlSnapshot loaded = storage.load().valueOrFailure("ACL should load through fake S3 endpoint");
            storage.save(
                    AccessControlSnapshot.singleFile(ACL_FILE, "saved-acl".getBytes(StandardCharsets.UTF_8)),
                    new AccessControlSaveRequest("save fake S3 ACL", new UserEmail("tester", "tester@example.test")));

            assertThat(loaded.files().get(ACL_FILE)).containsExactly("acl".getBytes(StandardCharsets.UTF_8));
            assertThat(s3.getObject("acl-bucket", "bootstrap/orion.xml"))
                    .containsExactly("saved-acl".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsConfiguredPathsEscapingPrefix() {
        S3AccessControlStorage storage = new S3AccessControlStorage(
                s3Config("s3://acl-bucket/bootstrap", List.of("../orion.xml")),
                new RecordingS3Client());

        assertThatThrownBy(() -> storage.save(
                AccessControlSnapshot.singleFile("../orion.xml", "acl".getBytes(StandardCharsets.UTF_8)),
                new AccessControlSaveRequest("save escaping ACL", new UserEmail("tester", "tester@example.test"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("S3 ACL object path escapes storage prefix: ../orion.xml");
    }

    @Test
    void rejectsUnsupportedS3SecretReference() {
        OrionConfiguration.BootstrapAccessControlConfig config = s3Config("s3://acl-bucket/bootstrap", List.of(ACL_FILE));
        config.getAuth().put("accessKeyId", "orion");
        config.getAuth().put("secretAccessKey", "inline-secret");

        assertThatThrownBy(() -> new S3AccessControlStorage(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("auth.secretAccessKey must use env: or file: reference");
    }

    @Test
    void resolverUsesS3StorageForS3Location() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().getAccessControl().setLocation("s3://acl-bucket/bootstrap");
        configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));

        AccessControlStorage storage = new AccessControlStorageResolver(configuration, failingGitRepositoryProvider()).resolve();

        assertThat(storage).isInstanceOf(S3AccessControlStorage.class);
    }

    private OrionConfiguration.BootstrapAccessControlConfig s3Config(String location, List<String> paths) {
        OrionConfiguration.BootstrapAccessControlConfig config = new OrionConfiguration.BootstrapAccessControlConfig();
        config.setLocation(location);
        config.setPaths(paths);
        return config;
    }

    private static final class RecordingS3Client implements S3AccessControlStorage.S3ObjectClient {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public Optional<byte[]> readObject(String bucket, String key) {
            byte[] content = objects.get(objectId(bucket, key));
            if (content == null) {
                return Optional.empty();
            }
            return Optional.of(content.clone());
        }

        @Override
        public void writeObject(String bucket, String key, byte[] content) {
            put(bucket, key, content);
        }

        private void put(String bucket, String key, byte[] content) {
            objects.put(objectId(bucket, key), content.clone());
        }

        private byte[] get(String bucket, String key) {
            byte[] content = objects.get(objectId(bucket, key));
            return content == null ? null : content.clone();
        }

        private String objectId(String bucket, String key) {
            return bucket + "/" + key;
        }
    }

    private GitRepositoryProvider failingGitRepositoryProvider() {
        return new GitRepositoryProvider() {
            @Override
            public boolean exists(String repositoryName) {
                throw new AssertionError("S3 ACL storage must not use local repository provider");
            }

            @Override
            public Result<GitRepository> find(String repositoryName) {
                throw new AssertionError("S3 ACL storage must not use local repository provider");
            }

            @Override
            public Result<GitRepository> findOrCreate(String repositoryName) {
                throw new AssertionError("S3 ACL storage must not use local repository provider");
            }
        };
    }

    private static final class FakeS3Server implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        private FakeS3Server(HttpServer server) {
            this.server = server;
        }

        private static FakeS3Server start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeS3Server fakeS3 = new FakeS3Server(server);
            server.createContext("/", fakeS3::handle);
            server.start();
            return fakeS3;
        }

        private String endpoint() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void putObject(String bucket, String key, byte[] content) {
            objects.put(objectId(bucket, key), content.clone());
        }

        private byte[] getObject(String bucket, String key) {
            byte[] content = objects.get(objectId(bucket, key));
            assertThat(content).as("S3 object %s/%s", bucket, key).isNotNull();
            return content.clone();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String objectId = objectId(exchange.getRequestURI().getPath());
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] content = objects.get(objectId);
                if (content == null) {
                    writeXml(exchange, HttpURLConnection.HTTP_NOT_FOUND, """
                            <Error><Code>NoSuchKey</Code><Message>Not found</Message></Error>
                            """);
                    return;
                }
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, content.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(content);
                }
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                byte[] content = exchange.getRequestBody().readAllBytes();
                objects.put(objectId, content);
                writeXml(exchange, HttpURLConnection.HTTP_OK, "<PutObjectResult/>");
                return;
            }
            writeXml(exchange, HttpURLConnection.HTTP_BAD_METHOD, """
                    <Error><Code>MethodNotAllowed</Code></Error>
                    """);
        }

        private static String objectId(String bucket, String key) {
            return bucket + "/" + key;
        }

        private static String objectId(String requestPath) {
            String path = URLDecoder.decode(requestPath, StandardCharsets.UTF_8);
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        }

        private static void writeXml(com.sun.net.httpserver.HttpExchange exchange, int status, String content)
                throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
