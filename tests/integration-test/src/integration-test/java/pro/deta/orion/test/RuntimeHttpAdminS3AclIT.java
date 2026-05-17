package pro.deta.orion.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeHttpAdminS3AclIT {
    private static final String ACL_FILE = "orion.xml";
    private static final String BUCKET = "orion-acl";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void postAccessControlUpdatesS3StorageAndSurvivesRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-s3");
        Path secretFile = tempDir.resolve("s3-secret.txt");
        Files.writeString(secretFile, "fake-s3-secret");

        TestPorts.Batch s3Ports = TestPorts.nextBatch();
        try (FakeS3Server s3 = FakeS3Server.start(s3Ports.http())) {
            s3.putObject(BUCKET, "bootstrap/" + ACL_FILE, serialize(defaultAccessControlWithUsers("s3-bootstrap-user")));
            OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot, config -> {
                config.getBootstrap().getAccessControl().setLocation("s3://" + BUCKET + "/bootstrap");
                config.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));
                config.getBootstrap().getAccessControl().getAuth().put("endpoint", s3.endpoint());
                config.getBootstrap().getAccessControl().getAuth().put("region", "us-east-1");
                config.getBootstrap().getAccessControl().getAuth().put("accessKeyId", "fake-s3-access");
                config.getBootstrap().getAccessControl().getAuth().put("secretAccessKey", secretFile.toUri().toString());
            });

            try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
                RuntimeHttpTestSupport.HttpResponse initialAcl = RuntimeHttpTestSupport.request(
                        "GET",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken(orion)));
                assertThat(initialAcl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
                assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8)))
                        .contains("root", "s3-bootstrap-user");
                assertThat(orionRoot.resolve("repos").resolve("orion")).doesNotExist();

                RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken(orion)),
                        "application/xml",
                        serialize(defaultAccessControlWithUsers("s3-updated-user")));

                assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
                assertUserAuthenticates(orion, "s3-updated-user");
            }

            AccessControl savedAcl = deserialize(s3.getObject(BUCKET, "bootstrap/" + ACL_FILE));
            assertThat(userIds(savedAcl)).contains("root", "s3-updated-user");
            assertThat(userIds(savedAcl)).doesNotContain("s3-bootstrap-user");

            try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
                assertUserAuthenticates(restarted, "s3-updated-user");
                RuntimeHttpTestSupport.HttpResponse aclAfterRestart = RuntimeHttpTestSupport.request(
                        "GET",
                        restarted.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken(restarted)));
                assertThat(userIds(aclAfterRestart.body().getBytes(StandardCharsets.UTF_8)))
                        .contains("root", "s3-updated-user");
            }
        }
    }

    private static String rootToken(RuntimeHttpTestSupport.StartedOrion orion) throws IOException {
        return TestBearerTokens.issueToken(
                orion.httpUrl("/api/admin/token"),
                "root",
                TEST_PASSWORD.toCharArray(),
                600);
    }

    private static AccessControl defaultAccessControlWithUsers(String... extraUserIds) {
        AccessControlDraft draft = ACLUtil.generateDefaultAccessControl(
                TEST_PASSWORD_HASH,
                AccessControl.CredentialType.SHA1).toDraft();
        for (String userId : extraUserIds) {
            draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                    .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH));
        }
        return draft.toAccessControl();
    }

    private static byte[] serialize(AccessControl accessControl) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serialize(accessControl, output);
            return output.toByteArray();
        }
    }

    private static AccessControl deserialize(byte[] content) throws IOException {
        return new XmlService().deserialize(new ByteArrayInputStream(content));
    }

    private static List<String> userIds(byte[] content) throws IOException {
        return userIds(deserialize(content));
    }

    private static List<String> userIds(AccessControl accessControl) {
        List<String> userIds = new ArrayList<>();
        for (AccessControl.User user : accessControl.getUsers()) {
            userIds.add(user.getId());
        }
        return userIds;
    }

    private static void assertUserAuthenticates(RuntimeHttpTestSupport.StartedOrion orion, String userId) {
        assertThat(orion.accessControlService().authenticateUser(
                userId,
                TEST_PASSWORD.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }

    private static final class FakeS3Server implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        private FakeS3Server(HttpServer server) {
            this.server = server;
        }

        private static FakeS3Server start(int port) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
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

        private void handle(HttpExchange exchange) throws IOException {
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

        private static void writeXml(HttpExchange exchange, int status, String content) throws IOException {
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
