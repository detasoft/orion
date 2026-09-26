package pro.deta.orion.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;

class RuntimeHttpAdminAclUpdateIT {
    private static final String ACL_FILE = "orion.xml";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void configurationStatusIsAdminOnlyAndReportsTheActiveGitRevision() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-status"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            RuntimeHttpTestSupport.HttpResponse withoutToken = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/configuration/status"), null);
            assertThat(withoutToken.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);

            String token = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"), 600);
            RuntimeHttpTestSupport.HttpResponse acl = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token));
            RuntimeHttpTestSupport.HttpResponse status = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/configuration/status"), TestBearerTokens.bearer(token));
            assertThat(status.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            JsonNode json = new ObjectMapper().readTree(status.body());
            assertThat(json.get("storedRevision").asText()).isEqualTo(acl.etag().replace("\"", ""));
            assertThat(json.get("activeRevision").asText()).isEqualTo(acl.etag().replace("\"", ""));
            assertThat(json.get("validation").asText()).isEqualTo("valid");
            assertThat(json.size()).isEqualTo(3);
        }
    }

    @Test
    void postAccessControlReloadsRuntimeAclAndSurvivesRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-update");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);
        char[] rootPassword;

        RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration);
        try {
            rootPassword = orion.accessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create())
                    .clone();
            String token = TestBearerTokens.issueToken(
                    orion.httpUrl("/api/admin/token"),
                    "root",
                    rootPassword,
                    600);

            RuntimeHttpTestSupport.HttpResponse initialAcl = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(initialAcl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8))).containsExactly("root");

            byte[] updatedAcl = serialize(accessControlWithPasswordUser("http-updated-user"));
            RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token),
                    "application/xml",
                    updatedAcl,
                    initialAcl.etag());

            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertUserAuthenticates(orion, "http-updated-user");
            assertThat(orion.accessControlService().authenticateUser(
                    "root",
                    String.valueOf(rootPassword).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
        } finally {
            orion.close();
        }

        try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
            assertUserAuthenticates(restarted, "http-updated-user");
            assertThat(userIds(readFileFromAclRepository(restarted))).containsExactly("http-updated-user");
        }
    }

    @Test
    void invalidAccessControlPostKeepsActiveAndStoredAclUnchanged() throws Exception {
        Path orionRoot = tempDir.resolve("orion-invalid-update");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            char[] rootPassword = orion.accessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create())
                    .clone();
            String token = TestBearerTokens.issueToken(
                    orion.httpUrl("/api/admin/token"),
                    "root",
                    rootPassword,
                    600);
            byte[] storedBefore = readFileFromAclRepository(orion);
            RuntimeHttpTestSupport.HttpResponse activeBefore = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));

            RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token),
                    "application/xml",
                    "<AccessControl><users>".getBytes(StandardCharsets.UTF_8),
                    activeBefore.etag());

            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertThat(readFileFromAclRepository(orion)).containsExactly(storedBefore);
            RuntimeHttpTestSupport.HttpResponse activeAfter = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(activeAfter.body()).isEqualTo(activeBefore.body());
            assertThat(orion.accessControlService().authenticateUser(
                    "root",
                    String.valueOf(rootPassword).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Success.class);
        }
    }

    @Test
    void staleAccessControlPostCannotReplaceANewerCommit() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-stale"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"), 600);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token));
            assertThat(initial.etag()).isNotBlank();

            RuntimeHttpTestSupport.HttpResponse missingRevision = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token),
                    "application/xml", withPasswordUser(initial.body(), "missing"));
            assertThat(missingRevision.status()).isEqualTo(428);

            RuntimeHttpTestSupport.HttpResponse first = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token),
                    "application/xml", withPasswordUser(initial.body(), "first"), initial.etag());
            assertThat(first.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            byte[] storedAfterFirst = readFileFromAclRepository(orion);

            RuntimeHttpTestSupport.HttpResponse stale = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token),
                    "application/xml", withPasswordUser(initial.body(), "second"), initial.etag());
            assertThat(stale.status()).isEqualTo(HttpURLConnection.HTTP_CONFLICT);
            assertThat(readFileFromAclRepository(orion)).containsExactly(storedAfterFirst);
            assertUserAuthenticates(orion, "first");
        }
    }

    private static AccessControl accessControlWithPasswordUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH));
        return draft.toAccessControl();
    }

    private static byte[] withPasswordUser(String originalXml, String userId) throws IOException {
        AccessControlDraft draft = new XmlService().deserialize(
                new ByteArrayInputStream(originalXml.getBytes(StandardCharsets.UTF_8))).toDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH));
        return serialize(draft.toAccessControl());
    }

    private static byte[] serialize(AccessControl accessControl) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serialize(accessControl, output);
            return output.toByteArray();
        }
    }

    private static List<String> userIds(byte[] content) throws IOException {
        AccessControl accessControl = new XmlService().deserialize(new ByteArrayInputStream(content));
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

    private static byte[] readFileFromAclRepository(RuntimeHttpTestSupport.StartedOrion orion) throws Exception {
        return orion.repositoryProvider()
                .openForRead("orion")
                .valueOrFailure("ACL repository should exist")
                .loadFiles(
                        orion.configuration().getBootstrap().getAccessControl().selectedRef(),
                        List.of(ACL_FILE))
                .files()
                .get(ACL_FILE).content();
    }
}
