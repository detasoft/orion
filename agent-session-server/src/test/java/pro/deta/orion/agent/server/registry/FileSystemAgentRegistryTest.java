package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemAgentRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void registeredAgentSurvivesCloseAndReopen() throws AgentRegistryException {
        Path root = temporaryDirectory.resolve("agents");
        AgentId agentId = new AgentId("agent-1");

        AgentRecord registered;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(agentId)).isEmpty();
            registered = registry.register(agentId, "Build agent");
            assertThat(registry.find(agentId)).contains(registered);
        }

        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(agentId)).contains(registered);
        }
    }

    @Test
    void duplicateRegistrationPreservesExistingState() throws AgentRegistryException {
        try (FileSystemAgentRegistry registry = registry()) {
            AgentRecord first = registry.register(new AgentId("agent-1"), "Build agent");

            AgentRecord duplicate = registry.register(new AgentId("agent-1"), "Build agent");

            assertThat(duplicate).isSameAs(first);
        }
    }

    @Test
    void conflictingDuplicateDoesNotReplaceExistingRecord() throws AgentRegistryException {
        AgentId agentId = new AgentId("agent-1");
        try (FileSystemAgentRegistry registry = registry()) {
            AgentRecord first = registry.register(agentId, "Build agent");

            assertThatThrownBy(() -> registry.register(agentId, "Other agent"))
                    .isInstanceOf(AgentRegistryException.class)
                    .extracting(failure -> ((AgentRegistryException) failure).reason())
                    .isEqualTo(AgentRegistryException.Reason.CONFLICT);
            assertThat(registry.find(agentId)).contains(first);
        }
    }

    @Test
    void registrationsForIndependentAgentsRemainIsolated() throws AgentRegistryException {
        try (FileSystemAgentRegistry registry = registry()) {
            AgentRecord first = registry.register(new AgentId("agent-1"), "First agent");
            AgentRecord second = registry.register(new AgentId("agent-2"), "Second agent");

            assertThat(registry.find(first.agentId())).contains(first);
            assertThat(registry.find(second.agentId())).contains(second);
        }
    }

    @Test
    void closedRegistryRejectsOperations() throws AgentRegistryException {
        FileSystemAgentRegistry registry = registry();
        registry.close();

        assertThatThrownBy(() -> registry.find(new AgentId("agent-1")))
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(AgentRegistryException.Reason.CLOSED);
    }

    private FileSystemAgentRegistry registry() throws AgentRegistryException {
        return new FileSystemAgentRegistry(temporaryDirectory.resolve("agents"));
    }
}
