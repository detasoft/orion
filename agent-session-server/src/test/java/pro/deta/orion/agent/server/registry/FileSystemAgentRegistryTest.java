package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentLabel;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemAgentRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void registeredAgentSurvivesCloseAndReopen() throws AgentRegistryException {
        Path root = temporaryDirectory.resolve("agents");
        AgentLabel agentLabel = new AgentLabel("agent-1");

        AgentRecord registered;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(agentLabel)).isEmpty();
            registered = registry.register(agentLabel, "Build agent");
            assertThat(registry.find(agentLabel)).contains(registered);
        }

        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(agentLabel)).contains(registered);
        }
    }

    @Test
    void duplicateRegistrationPreservesExistingState() throws AgentRegistryException {
        try (FileSystemAgentRegistry registry = registry()) {
            AgentRecord first = registry.register(new AgentLabel("agent-1"), "Build agent");

            AgentRecord duplicate = registry.register(new AgentLabel("agent-1"), "Build agent");

            assertThat(duplicate).isSameAs(first);
        }
    }

    @Test
    void conflictingDuplicateDoesNotReplaceExistingRecord() throws AgentRegistryException {
        AgentLabel agentLabel = new AgentLabel("agent-1");
        try (FileSystemAgentRegistry registry = registry()) {
            AgentRecord first = registry.register(agentLabel, "Build agent");

            assertThatThrownBy(() -> registry.register(agentLabel, "Other agent"))
                    .isInstanceOf(AgentRegistryException.class)
                    .extracting(failure -> ((AgentRegistryException) failure).reason())
                    .isEqualTo(AgentRegistryException.Reason.CONFLICT);
            assertThat(registry.find(agentLabel)).contains(first);
        }
    }

    @Test
    void registrationsForIndependentAgentsRemainIsolated() throws AgentRegistryException {
        try (FileSystemAgentRegistry registry = registry()) {
            AgentRecord first = registry.register(new AgentLabel("agent-1"), "First agent");
            AgentRecord second = registry.register(new AgentLabel("agent-2"), "Second agent");

            assertThat(registry.find(first.agentLabel())).contains(first);
            assertThat(registry.find(second.agentLabel())).contains(second);
        }
    }

    @Test
    void closedRegistryRejectsOperations() throws AgentRegistryException {
        FileSystemAgentRegistry registry = registry();
        registry.close();

        assertThatThrownBy(() -> registry.find(new AgentLabel("agent-1")))
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(AgentRegistryException.Reason.CLOSED);
    }

    private FileSystemAgentRegistry registry() throws AgentRegistryException {
        return new FileSystemAgentRegistry(temporaryDirectory.resolve("agents"));
    }
}
