package pro.deta.orion.test;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.test.integration.git.GitRepositoryFixture;
import pro.deta.orion.test.integration.git.GitSshTestServer;
import pro.deta.orion.util.KeyUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.PASSWORD_ENV;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.materialBytes;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.runtimeComponent;

/** Orion bootstraps over SSH from JGit, publishes an ACL update, and reloads it after restart. */
class RuntimeHttpAdminRemoteGitSshAclIT {
    private static final String REF = "refs/heads/main";
    private static final String ACL_FILE = "orion.xml";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void postAccessControlUpdatesRemoteGitSshStorageAndSurvivesRestart() throws Exception {
        var configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("target"));
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        var environment = Map.of(PASSWORD_ENV, "bootstrap-test-password");
        byte[] material = materialBytes(configuration, environment);
        Path repositoriesRoot = tempDir.resolve("git-ssh");
        Path remoteAclRepository = repositoriesRoot.resolve("orion-acl.git");
        Files.createDirectories(repositoriesRoot);
        GitRepositoryFixture.seedBareRepository(remoteAclRepository, tempDir.resolve("remote-acl-seed"), "main",
                Map.of(ACL_FILE, serialize(OrionDocument.withAccessControl(
                                defaultAccessControlWithUsers("remote-bootstrap-user"))),
                        "material.p12", material));

        Path privateKey = copyPrivateKey("e2e/trusted-user-rsa.pem", tempDir.resolve("trusted-user-rsa.pem"));
        KeyPair userKey = KeyUtils.readRSAKeyPair(privateKey)
                .valueOrFailure("Trusted user SSH key should load");
        KeyPair hostKey = KeyUtils.readKeyFromFile(copyPrivateKey(
                        "e2e/server-rsa.pem", tempDir.resolve("server-rsa.pem")))
                .valueOrFailure("Server SSH key should load");
        try (GitSshTestServer gitServer = GitSshTestServer.start(
                repositoriesRoot, "git", hostKey, userKey.getPublic(), TestPorts.nextBatch().ssh())) {
            var authentication = Map.of("credentialKind", "private-key",
                    "credential", privateKey.toRealPath().toUri().toString(),
                    "knownHosts", org.apache.sshd.common.config.keys.PublicKeyEntry.toString(hostKey.getPublic()));
            for (BootstrapSourceConfig source : List.of(configuration.getBootstrap().getAccessControl(),
                    configuration.getBootstrap().getKeyMaterial())) {
                source.setLocation("git+" + gitServer.repositoryUrl("orion-acl.git"));
                source.setRef(REF);
                source.setAuth(authentication);
            }

            var http = configuration.getTransport().getHttp();
            URL aclUrl = new URL("http", http.getAddress(), http.getPort(), "/api/admin/acl");
            URL tokenUrl = new URL("http", http.getAddress(), http.getPort(), "/api/admin/token");
            for (int launch = 0; launch < 2; launch++) {
                try (var bootstrap = BootstrapContext.open(configuration, environment)) {
                    var component = runtimeComponent(configuration, bootstrap);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                        lifecycle.waitForStarting();
                        String authorization = TestBearerTokens.bearer(rootToken(tokenUrl));
                        var initialAcl = RuntimeHttpTestSupport.request("GET", aclUrl, authorization);
                        assertThat(initialAcl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
                        String expectedUser = launch == 0 ? "remote-bootstrap-user" : "remote-git-updated-user";
                        assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8)))
                                .contains("root", expectedUser);
                        assertUserAuthenticates(component.orionAccessControlService(), expectedUser);
                        String cache = bootstrap.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                                .repositoryName().orElseThrow();
                        assertThat(bootstrap.repositoryProvider().repositoryNames()).doesNotContain(cache);

                        if (launch == 0) {
                            var current = new XmlService().deserializeDocument(new ByteArrayInputStream(
                                    initialAcl.body().getBytes(StandardCharsets.UTF_8)));
                            var updated = current.replaceAccessControl(
                                    defaultAccessControlWithUsers("remote-git-updated-user"));
                            byte[] materialBeforeUpdate = readFileFromRepository(remoteAclRepository, "material.p12");
                            var update = RuntimeHttpTestSupport.request("POST", aclUrl, authorization,
                                    "application/xml", serialize(updated));
                            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
                            assertUserAuthenticates(component.orionAccessControlService(), "remote-git-updated-user");
                            assertThat(readFileFromRepository(remoteAclRepository, "material.p12"))
                                    .isEqualTo(materialBeforeUpdate);
                        } else {
                            assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8)))
                                    .doesNotContain("remote-bootstrap-user");
                        }
                    } finally {
                        lifecycle.shutdownApplication();
                        lifecycle.waitForShutdown();
                    }
                }
                assertThat(userIds(readFileFromRepository(remoteAclRepository, ACL_FILE)))
                        .contains("root", "remote-git-updated-user")
                        .doesNotContain("remote-bootstrap-user");
            }
        }
    }

    private static String rootToken(URL tokenUrl) throws IOException {
        return TestBearerTokens.issueToken(tokenUrl, "root", TEST_PASSWORD.toCharArray(), 600);
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

    private static byte[] serialize(OrionDocument document) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serializeDocument(document, output);
            return output.toByteArray();
        }
    }

    private static List<String> userIds(byte[] content) throws IOException {
        var accessControl = new XmlService().deserialize(new ByteArrayInputStream(content));
        List<String> userIds = new ArrayList<>();
        for (AccessControl.User user : accessControl.getUsers()) {
            userIds.add(user.getId());
        }
        return userIds;
    }

    private static void assertUserAuthenticates(OrionAccessControlService accessControl, String userId) {
        assertThat(accessControl.authenticateUser(userId, TEST_PASSWORD.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }

    private static byte[] readFileFromRepository(Path repositoryPath, String filePath) throws IOException {
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile());
             RevWalk revWalk = new RevWalk(repository)) {
            var head = repository.resolve(REF);
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
