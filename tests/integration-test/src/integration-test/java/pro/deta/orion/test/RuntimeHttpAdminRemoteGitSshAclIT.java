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
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.test.integration.git.GitRepositoryFixture;
import pro.deta.orion.test.integration.git.GitSshTestServer;
import pro.deta.orion.util.KeyUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeHttpAdminRemoteGitSshAclIT {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "orion.xml";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void postAccessControlUpdatesRemoteGitSshStorageAndSurvivesRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-remote-git");
        Path repositoriesRoot = tempDir.resolve("git-ssh");
        Path remoteAclRepository = repositoriesRoot.resolve("orion-acl.git");
        Files.createDirectories(repositoriesRoot);
        GitRepositoryFixture.seedBareRepository(
                remoteAclRepository,
                tempDir.resolve("remote-acl-seed"),
                BRANCH,
                Map.of(ACL_FILE, serialize(defaultAccessControlWithUsers("remote-bootstrap-user"))));

        Path privateKey = copyPrivateKey("e2e/trusted-user-rsa.pem", tempDir.resolve("trusted-user-rsa.pem"));
        KeyPair userKey = KeyUtils.readRSAKeyPair(privateKey)
                .valueOrFailure("Trusted user SSH key should load");
        KeyPair hostKey = KeyUtils.readKeyFromFile(copyPrivateKey(
                        "e2e/server-rsa.pem",
                        tempDir.resolve("server-rsa.pem")))
                .valueOrFailure("Server SSH key should load");

        TestPorts.Batch gitPorts = TestPorts.nextBatch();
        try (GitSshTestServer gitServer = GitSshTestServer.start(
                repositoriesRoot,
                "git",
                hostKey,
                userKey.getPublic(),
                gitPorts.ssh())) {
            Path knownHosts = tempDir.resolve("known_hosts");
            Files.writeString(knownHosts, gitServer.knownHostsLine() + "\n");
            OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot, config -> {
                config.getBootstrap().getAccessControl().setLocation("git+" + gitServer.repositoryUrl("orion-acl.git"));
                config.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));
                config.getBootstrap().getAccessControl().getAuth().put("privateKey", privateKey.toUri().toString());
                config.getBootstrap().getAccessControl().getAuth().put("knownHosts", knownHosts.toUri().toString());
            });

            try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
                RuntimeHttpTestSupport.HttpResponse initialAcl = RuntimeHttpTestSupport.request(
                        "GET",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken(orion)));
                assertThat(initialAcl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
                assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8)))
                        .contains("root", "remote-bootstrap-user");
                assertThat(orionRoot.resolve("repos").resolve("orion")).doesNotExist();

                RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken(orion)),
                        "application/xml",
                        serialize(defaultAccessControlWithUsers("remote-git-updated-user")));

                assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
                assertUserAuthenticates(orion, "remote-git-updated-user");
            }

            AccessControl savedAcl = deserialize(readFileFromRepository(remoteAclRepository, ACL_FILE));
            assertThat(userIds(savedAcl)).contains("root", "remote-git-updated-user");
            assertThat(userIds(savedAcl)).doesNotContain("remote-bootstrap-user");

            try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
                assertUserAuthenticates(restarted, "remote-git-updated-user");
                RuntimeHttpTestSupport.HttpResponse aclAfterRestart = RuntimeHttpTestSupport.request(
                        "GET",
                        restarted.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken(restarted)));
                assertThat(userIds(aclAfterRestart.body().getBytes(StandardCharsets.UTF_8)))
                        .contains("root", "remote-git-updated-user");
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

    private static byte[] readFileFromRepository(Path repositoryPath, String filePath) throws IOException {
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile());
             RevWalk revWalk = new RevWalk(repository)) {
            ObjectId head = repository.resolve(Constants.R_HEADS + BRANCH);
            assertThat(head).isNotNull();
            var commit = revWalk.parseCommit(head);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, filePath, commit.getTree())) {
                assertThat(treeWalk).isNotNull();
                return repository.open(treeWalk.getObjectId(0)).getBytes();
            }
        }
    }

    private static Path copyPrivateKey(String resourceName, Path target) throws IOException {
        try (InputStream input = RuntimeHttpAdminRemoteGitSshAclIT.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + resourceName);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.setPosixFilePermissions(target, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
        return target;
    }
}
