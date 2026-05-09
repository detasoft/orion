package pro.deta.orion.test;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.util.NetworkUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrionStartupIT {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "orion.xml";

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

    private static StartedOrion startServerWithConfig(OrionConfiguration orionConfiguration) {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(() -> orionConfiguration)
                .build();
        OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();
        ApplicationState state = lifecycle.runApplication();
        assertThat(state).isEqualTo(ApplicationState.UP);
        lifecycle.waitForStarting();
        return new StartedOrion(lifecycle);
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

    private record StartedOrion(OrionApplicationLifecycle lifecycle) implements AutoCloseable {
        @Override
        public void close() {
            lifecycle.beginShutdown();
            lifecycle.waitForShutdown();
        }
    }
}
