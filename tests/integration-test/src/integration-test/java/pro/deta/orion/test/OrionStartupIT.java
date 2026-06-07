package pro.deta.orion.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;
import pro.deta.orion.transport.http.OrionAccessControlSchemaRoute;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class OrionStartupIT {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "orion.xml";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void freshStartCreatesInitialStorageAndStopsCleanly() throws IOException {
        Path orionRoot = tempDir.resolve("orion");

        try (StartedOrion ignored = startServerWithConfig(serverConfiguration(orionRoot))) {
            assertInitialConfigurationCreated(orionRoot);
        }

        assertInitialConfigurationCreated(orionRoot);
    }

    @Test
    void restartUsesExistingStorageWithoutRewritingAcl() throws IOException {
        Path orionRoot = tempDir.resolve("orion");

        try (StartedOrion ignored = startServerWithConfig(serverConfiguration(orionRoot))) {
            assertInitialConfigurationCreated(orionRoot);
        }

        ObjectId initialAclCommit = aclHead(orionRoot);

        try (StartedOrion ignored = startServerWithConfig(serverConfiguration(orionRoot))) {
            assertInitialConfigurationCreated(orionRoot);
            assertThat(aclHead(orionRoot)).isEqualTo(initialAclCommit);
        }
    }

    @Test
    void createdAccessControlConfigurationMatchesPublishedSchema() throws Exception {
        Path orionRoot = tempDir.resolve("orion");

        try (StartedOrion orion = startServerWithConfig(serverConfiguration(orionRoot))) {
            byte[] aclContent = readFileFromAclRepository(orionRoot);

            Map<String, Object> validation = validateAccessControlXml(orion, aclContent);

            assertThat(validation).containsEntry("valid", true);
            assertThat(validation).doesNotContainKey("message");
        }
    }

    @Test
    void remoteGitAclIsLoadedAndSavedThroughRuntime() throws Exception {
        Path orionRoot = tempDir.resolve("orion");
        Path remoteAclRepository = tempDir.resolve("remote-acl.git");
        seedRemoteAclRepository(remoteAclRepository, accessControlWithUsers("root", "remote-user"));
        OrionConfiguration configuration = serverConfiguration(orionRoot);
        configuration.getBootstrap().getAccessControl().setLocation("git+" + remoteAclRepository.toUri());
        configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));

        try (StartedOrion orion = startServerWithConfig(configuration)) {
            AccessControl loadedAcl = deserialize(orion.accessControlService().accessControlConfigurationFile());

            assertThat(hasUser(loadedAcl, "remote-user")).isTrue();
            assertThat(aclRepository(orionRoot)).doesNotExist();

            orion.accessControlService().saveAccessControlConfigurationFile(
                    serialize(accessControlWithUsers("root", "saved-remote-user")));
        }

        AccessControl savedAcl = deserialize(readFileFromRepository(remoteAclRepository, ACL_FILE));
        assertThat(hasUser(savedAcl, "remote-user")).isFalse();
        assertThat(hasUser(savedAcl, "saved-remote-user")).isTrue();
    }

    @Test
    void s3AclIsLoadedAndSavedThroughRuntime() throws Exception {
        Path orionRoot = tempDir.resolve("orion-s3");
        Path secretFile = tempDir.resolve("s3-secret.txt");
        String bucketName = "orion-acl-" + UUID.randomUUID();

        try (MinioS3TestServer s3 = MinioS3TestServer.start(bucketName)) {
            Files.writeString(secretFile, s3.secretAccessKey());
            s3.putObject("bootstrap/orion.xml", serialize(accessControlWithUsers("root", "s3-user")));
            OrionConfiguration configuration = serverConfiguration(orionRoot);
            configuration.getBootstrap().getAccessControl().setLocation("s3://" + s3.bucketName() + "/bootstrap");
            configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_FILE));
            configuration.getBootstrap().getAccessControl().getAuth().put("endpoint", s3.endpoint());
            configuration.getBootstrap().getAccessControl().getAuth().put("region", "us-east-1");
            configuration.getBootstrap().getAccessControl().getAuth().put("accessKeyId", s3.accessKeyId());
            configuration.getBootstrap().getAccessControl().getAuth().put("secretAccessKey", secretFile.toUri().toString());

            try (StartedOrion orion = startServerWithConfig(configuration)) {
                AccessControl loadedAcl = deserialize(orion.accessControlService().accessControlConfigurationFile());

                assertThat(hasUser(loadedAcl, "s3-user")).isTrue();
                assertThat(aclRepository(orionRoot)).doesNotExist();

                orion.accessControlService().saveAccessControlConfigurationFile(
                        serialize(accessControlWithUsers("root", "saved-s3-user")));
            }

            AccessControl savedAcl = deserialize(s3.getObject("bootstrap/orion.xml"));
            assertThat(hasUser(savedAcl, "s3-user")).isFalse();
            assertThat(hasUser(savedAcl, "saved-s3-user")).isTrue();
        }
    }

    @Test
    void serverShutdownDoesNotBreakStandaloneJGitPushInSameJvm() throws Exception {
        Path orionRoot = tempDir.resolve("orion");

        try (StartedOrion ignored = startServerWithConfig(serverConfiguration(orionRoot))) {
            assertInitialConfigurationCreated(orionRoot);
        }

        pushToBareRepository(tempDir.resolve("standalone-jgit"));
    }

    @Test
    void httpShutdownRequestStopsServerPromptly() throws Exception {
        Path orionRoot = tempDir.resolve("orion");

        StartedOrion orion = startServerWithConfig(serverConfiguration(orionRoot));
        boolean shutdownCompleted = false;
        try {
            String token = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);

            long startedAtNanos = System.nanoTime();
            int responseCode = postShutdown(orion, token);
            orion.lifecycle().waitForShutdown();
            shutdownCompleted = true;
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertThat(responseCode).isEqualTo(HttpURLConnection.HTTP_ACCEPTED);
            assertThat(elapsedMillis).isLessThan(2_000);
        } finally {
            if (!shutdownCompleted) {
                orion.close();
            }
        }
    }

    @Test
    void occupiedHttpPortFailsRootStartupAndStillShutsDownCleanly() throws Exception {
        Path orionRoot = tempDir.resolve("orion-port-conflict");
        OrionConfiguration configuration = serverConfiguration(orionRoot);
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttps().setEnabled(false);

        ServerSocket occupiedHttpPort = bindHttpPort(configuration);
        try {
            OrionComponent orionComponent = DaggerOrionComponent.builder()
                    .configurationProvider(() -> configuration)
                    .build();
            OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();

            assertThat(lifecycle.runApplication()).isEqualTo(ERR);
            assertThat(lifecycle.describeLifecycle())
                    .contains("orion: ERR", "transports: ERR", "http: ERR")
                    .doesNotContain("http: RUNNING");

            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
            lifecycle.waitForShutdown();

            occupiedHttpPort.close();
            assertCanBindHttpPort(configuration);
        } finally {
            occupiedHttpPort.close();
        }
    }

    private static StartedOrion startServerWithConfig(OrionConfiguration orionConfiguration) {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(() -> orionConfiguration)
                .build();
        OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();
        assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
        lifecycle.waitForStarting();
        return new StartedOrion(orionConfiguration, lifecycle, orionComponent.orionAccessControlService());
    }

    private static void assertInitialConfigurationCreated(Path orionRoot) throws IOException {
        assertThat(aclRepository(orionRoot).resolve("config")).exists();
        AccessControl accessControl = readAcl(orionRoot);
        assertThat(hasUser(accessControl, "root")).isTrue();
        assertThat(hasRole(accessControl, "ROOT")).isTrue();
        assertThat(hasGrant(accessControl, "ALL_REPOSITORY")).isTrue();
    }

    private static Path aclRepository(Path orionRoot) {
        return orionRoot.resolve("repos").resolve("orion");
    }

    private static AccessControl readAcl(Path orionRoot) throws IOException {
        byte[] content = readFileFromAclRepository(orionRoot);
        return new XmlService().deserialize(new ByteArrayInputStream(content));
    }

    private static ObjectId aclHead(Path orionRoot) throws IOException {
        try (Repository repository = FileRepositoryBuilder.create(aclRepository(orionRoot).toFile())) {
            ObjectId head = repository.resolve(Constants.R_HEADS + BRANCH);
            assertThat(head).isNotNull();
            return head;
        }
    }

    private static byte[] readFileFromAclRepository(Path orionRoot) throws IOException {
        return readFileFromRepository(aclRepository(orionRoot), ACL_FILE);
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

    private static void seedRemoteAclRepository(Path bareRepository, AccessControl accessControl) throws Exception {
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(bareRepository.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            // The bare repository is the remote ACL storage target.
        }

        Path seedWorktree = bareRepository.getParent().resolve("remote-acl-seed");
        try (Git seed = Git.init()
                .setDirectory(seedWorktree.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            Files.write(seedWorktree.resolve(ACL_FILE), serialize(accessControl));
            seed.add().addFilepattern(ACL_FILE).call();
            seed.commit()
                    .setAuthor("ACL Test", "acl@example.test")
                    .setCommitter("ACL Test", "acl@example.test")
                    .setMessage("seed remote ACL")
                    .call();
            seed.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
        }
    }

    private static byte[] serialize(AccessControl accessControl) throws IOException {
        try (var output = new java.io.ByteArrayOutputStream()) {
            new XmlService().serialize(accessControl, output);
            return output.toByteArray();
        }
    }

    private static AccessControl deserialize(byte[] content) throws IOException {
        return new XmlService().deserialize(new ByteArrayInputStream(content));
    }

    private static AccessControl accessControlWithUsers(String... userIds) {
        AccessControlDraft draft = ACLUtil.generateDefaultAccessControl("remote-root-password-hash").toDraft();
        for (String userId : userIds) {
            if (!"root".equalsIgnoreCase(userId)) {
                draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test"));
            }
        }
        return draft.toAccessControl();
    }

    private static Map<String, Object> validateAccessControlXml(StartedOrion orion, byte[] content) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) orion.httpUrl(OrionAccessControlSchemaRoute.URL_PATTERN)
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/xml");
        connection.setFixedLengthStreamingMode(content.length);
        try (var output = connection.getOutputStream()) {
            output.write(content);
        }

        assertThat(connection.getResponseCode())
                .as("ACL schema validation endpoint")
                .isEqualTo(HttpURLConnection.HTTP_OK);
        assertThat(connection.getContentType()).startsWith("application/json");

        try (InputStream input = connection.getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = OBJECT_MAPPER.readValue(input, Map.class);
            return response;
        }
    }

    private static int postShutdown(StartedOrion orion, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) orion.httpUrl("/api/admin/shutdown")
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", TestBearerTokens.bearer(token));
        connection.setFixedLengthStreamingMode(0);
        try (var output = connection.getOutputStream()) {
            // Send an explicit empty POST body.
        }
        return connection.getResponseCode();
    }

    private static ServerSocket bindHttpPort(OrionConfiguration configuration) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(
                InetAddress.getByName(configuration.getTransport().getHttp().getAddress()),
                configuration.getTransport().getHttp().getPort()));
        return socket;
    }

    private static void assertCanBindHttpPort(OrionConfiguration configuration) throws IOException {
        try (ServerSocket ignored = bindHttpPort(configuration)) {
            assertThat(ignored.isBound()).isTrue();
        }
    }

    private static boolean hasUser(AccessControl accessControl, String id) {
        for (AccessControl.User user : accessControl.getUsers()) {
            if (id.equals(user.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRole(AccessControl accessControl, String id) {
        for (AccessControl.Role role : accessControl.getRoles()) {
            if (id.equals(role.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGrant(AccessControl accessControl, String id) {
        for (AccessControl.Grant grant : accessControl.getGrants()) {
            if (id.equals(grant.getId())) {
                return true;
            }
        }
        return false;
    }

    private static OrionConfiguration serverConfiguration(Path orionRoot) throws IOException {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(orionRoot.toString());
        configuration.getStorage().setLocation(orionRoot.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:orion");

        TestPorts.nextBatch().configure(configuration);
        configuration.getTransport().getHttps().setEnabled(false);
        return configuration;
    }

    private static void pushToBareRepository(Path root) throws Exception {
        Path bareRepository = root.resolve("bare.git");
        Files.createDirectories(bareRepository);
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(bareRepository.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            // The bare repository is the target for a normal local push.
        }

        Path workTree = root.resolve("worktree");
        try (Git git = Git.init()
                .setDirectory(workTree.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Standalone JGit Test");
            git.getRepository().getConfig().setString("user", null, "email", "standalone-jgit@example.test");
            git.getRepository().getConfig().save();

            Files.writeString(workTree.resolve("README.md"), "standalone jgit push\n");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setAuthor("Standalone JGit Test", "standalone-jgit@example.test")
                    .setCommitter("Standalone JGit Test", "standalone-jgit@example.test")
                    .setMessage("verify standalone jgit push")
                    .call();
            git.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
        }
    }

    private record StartedOrion(
            OrionConfiguration configuration,
            OrionApplicationLifecycle lifecycle,
            OrionAccessControlServiceImpl accessControlService)
            implements AutoCloseable {
        private URL httpUrl(String path) throws IOException {
            return new URL(
                    "http",
                    configuration.getTransport().getHttp().getAddress(),
                    configuration.getTransport().getHttp().getPort(),
                    path);
        }

        @Override
        public void close() {
            lifecycle.shutdownApplication();
            lifecycle.waitForShutdown();
        }
    }

}
