package pro.deta.orion.test;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.config.schema.OrionConfiguration;
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
                    updatedAcl);

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
            assertThat(userIds(readFileFromAclRepository(orionRoot))).containsExactly("http-updated-user");
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
            byte[] storedBefore = readFileFromAclRepository(orionRoot);
            RuntimeHttpTestSupport.HttpResponse activeBefore = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));

            RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token),
                    "application/xml",
                    "<AccessControl><users>".getBytes(StandardCharsets.UTF_8));

            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertThat(readFileFromAclRepository(orionRoot)).containsExactly(storedBefore);
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

    private static AccessControl accessControlWithPasswordUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH));
        return draft.toAccessControl();
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

    private static byte[] readFileFromAclRepository(Path orionRoot) throws IOException {
        Path repositoryPath = orionRoot.resolve("repos").resolve("orion");
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile());
             RevWalk revWalk = new RevWalk(repository)) {
            ObjectId head = repository.resolve(Constants.R_HEADS + "master");
            assertThat(head).isNotNull();
            var commit = revWalk.parseCommit(head);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, ACL_FILE, commit.getTree())) {
                assertThat(treeWalk).isNotNull();
                return repository.open(treeWalk.getObjectId(0)).getBytes();
            }
        }
    }
}
