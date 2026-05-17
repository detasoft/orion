package pro.deta.orion.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeHttpAdminS3AclIT {
    private static final String ACL_FILE = "orion.xml";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void postAccessControlUpdatesS3StorageAndSurvivesRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-s3");
        Path secretFile = tempDir.resolve("s3-secret.txt");
        String bucketName = "orion-acl-" + UUID.randomUUID().toString().replace("-", "");

        try (MinioS3TestServer s3 = MinioS3TestServer.start(bucketName)) {
            Files.writeString(secretFile, s3.secretAccessKey());
            s3.putObject("bootstrap/" + ACL_FILE, serialize(defaultAccessControlWithUsers("s3-bootstrap-user")));
            OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot, config -> {
                config.getBootstrap().getAccessControl().setLocation("s3://" + s3.bucketName() + "/bootstrap");
                config.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));
                config.getBootstrap().getAccessControl().getAuth().put("endpoint", s3.endpoint());
                config.getBootstrap().getAccessControl().getAuth().put("region", "us-east-1");
                config.getBootstrap().getAccessControl().getAuth().put("accessKeyId", s3.accessKeyId());
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

            AccessControl savedAcl = deserialize(s3.getObject("bootstrap/" + ACL_FILE));
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
}
