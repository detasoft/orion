package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryOwnershipTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oneLiveRegistryExclusivelyOwnsItsRoot() throws AgentRegistryException {
        Path root = temporaryDirectory.resolve("agents");
        try (FileSystemAgentRegistry owner = new FileSystemAgentRegistry(root)) {
            assertOpenFails(root, AgentRegistryException.Reason.CONFLICT);
        }

        try (FileSystemAgentRegistry replacement = new FileSystemAgentRegistry(root)) {
            assertThat(replacement).isNotNull();
        }
    }

    @Test
    void symbolicRootAliasCannotAcquireASecondOwnership() throws IOException, AgentRegistryException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("agents"));
        Path alias = temporaryDirectory.resolve("agent-alias");
        Files.createSymbolicLink(alias, root);

        try (FileSystemAgentRegistry owner = new FileSystemAgentRegistry(root)) {
            assertOpenFails(alias, AgentRegistryException.Reason.CONFLICT);
        }
    }

    @Test
    void failedRecoveryReleasesRootOwnership() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("agents"));
        Path corrupt = root.resolve("invalid.agent");
        Files.write(corrupt, new byte[]{1, 2, 3});

        assertOpenFails(root, AgentRegistryException.Reason.STORED_CORRUPTION);
        Files.delete(corrupt);

        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry).isNotNull();
        } catch (AgentRegistryException e) {
            throw new AssertionError("Failed recovery retained root ownership", e);
        }
    }

    @Test
    void missingRootHierarchyIsCreated() throws AgentRegistryException {
        Path root = temporaryDirectory.resolve("one/two/agents");

        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(root).isDirectory();
        }
    }

    private static void assertOpenFails(Path root, AgentRegistryException.Reason reason) {
        assertThatThrownBy(() -> new FileSystemAgentRegistry(root))
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(reason);
    }
}
