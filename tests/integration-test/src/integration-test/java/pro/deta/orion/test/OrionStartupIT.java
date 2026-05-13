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
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.transport.http.OrionAccessControlSchemaRoute;
import pro.deta.orion.util.NetworkUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
    void serverShutdownDoesNotBreakStandaloneJGitPushInSameJvm() throws Exception {
        Path orionRoot = tempDir.resolve("orion");

        try (StartedOrion ignored = startServerWithConfig(serverConfiguration(orionRoot))) {
            assertInitialConfigurationCreated(orionRoot);
        }

        pushToBareRepository(tempDir.resolve("standalone-jgit"));
    }

    private static StartedOrion startServerWithConfig(OrionConfiguration orionConfiguration) {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(() -> orionConfiguration)
                .build();
        OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();
        ApplicationState state = lifecycle.runApplication();
        assertThat(state).isEqualTo(ApplicationState.UP);
        lifecycle.waitForStarting();
        return new StartedOrion(orionConfiguration, lifecycle);
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
        try (Repository repository = FileRepositoryBuilder.create(aclRepository(orionRoot).toFile());
             RevWalk revWalk = new RevWalk(repository)) {
            ObjectId head = repository.resolve(Constants.R_HEADS + BRANCH);
            assertThat(head).isNotNull();
            var commit = revWalk.parseCommit(head);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, ACL_FILE, commit.getTree())) {
                assertThat(treeWalk).isNotNull();
                return repository.open(treeWalk.getObjectId(0)).getBytes();
            }
        }
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
        Set<Integer> ports = new LinkedHashSet<>();
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(orionRoot.toString());
        configuration.getStorage().setLocation(orionRoot.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:orion");

        configuration.getTransport().getGit().setAddress("localhost");
        configuration.getTransport().getGit().setPort(freePort(ports));

        configuration.getTransport().getSsh().setAddress("localhost");
        configuration.getTransport().getSsh().setPort(freePort(ports));

        configuration.getTransport().getHttp().setAddress("localhost");
        configuration.getTransport().getHttp().setPort(freePort(ports));

        configuration.getTransport().getHttps().setEnabled(false);
        configuration.getTransport().getHttps().setAddress("localhost");
        configuration.getTransport().getHttps().setPort(freePort(ports));
        return configuration;
    }

    private static int freePort(Set<Integer> reservedPorts) throws IOException {
        for (int i = 0; i < 50; i++) {
            int port = NetworkUtils.findAvailablePort();
            if (reservedPorts.add(port)) {
                return port;
            }
        }
        throw new IOException("Failed to find an unused port for test configuration");
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

    private record StartedOrion(OrionConfiguration configuration, OrionApplicationLifecycle lifecycle)
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
            lifecycle.beginShutdown();
            lifecycle.waitForShutdown();
        }
    }
}
