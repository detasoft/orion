package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryDurabilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void temporaryForceFailurePublishesNothingAndOwnerRemainsUsable()
            throws AgentRegistryException {
        assertDefinedFailureLeavesOwnerUsable(FailurePoint.TEMPORARY_FORCE);
    }

    @Test
    void prePublicationFailurePublishesNothingAndOwnerRemainsUsable()
            throws AgentRegistryException {
        assertDefinedFailureLeavesOwnerUsable(FailurePoint.BEFORE_PUBLICATION);
    }

    @Test
    void postPublicationFailurePoisonsOwnerUntilReopen() throws AgentRegistryException {
        assertIndeterminateFailurePoisonsOwner(FailurePoint.AFTER_PUBLICATION);
    }

    @Test
    void moveFailurePoisonsOwnerUntilReopen() throws AgentRegistryException {
        assertIndeterminateFailurePoisonsOwner(FailurePoint.MOVE);
    }

    private void assertIndeterminateFailurePoisonsOwner(FailurePoint point)
            throws AgentRegistryException {
        Path root = temporaryDirectory.resolve("agents");
        AgentId agentId = new AgentId("agent-1");
        try (FileSystemAgentRegistry registry = FileSystemAgentRegistry.withOperations(
                root, new FailingOperations(point))) {
            assertFailureReason(
                    () -> registry.register(agentId, "Build agent"),
                    AgentRegistryException.Reason.INDETERMINATE);
            assertFailureReason(
                    () -> registry.find(agentId),
                    AgentRegistryException.Reason.INDETERMINATE);
            assertFailureReason(
                    () -> registry.register(new AgentId("agent-2"), "Other agent"),
                    AgentRegistryException.Reason.INDETERMINATE);
        }

        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            if (point == FailurePoint.AFTER_PUBLICATION) {
                assertThat(reopened.find(agentId))
                        .contains(new AgentRecord(
                                agentId, "Build agent", Optional.empty(), Optional.empty()));
            } else {
                assertThat(reopened.find(agentId)).isEmpty();
            }
        }
    }

    private void assertDefinedFailureLeavesOwnerUsable(FailurePoint point)
            throws AgentRegistryException {
        AgentId failedAgent = new AgentId("agent-1");
        AgentId successfulAgent = new AgentId("agent-2");
        try (FileSystemAgentRegistry registry = FileSystemAgentRegistry.withOperations(
                temporaryDirectory.resolve("agents"), new FailingOperations(point))) {
            assertFailureReason(
                    () -> registry.register(failedAgent, "Build agent"),
                    AgentRegistryException.Reason.IO_FAILURE);
            assertThat(registry.find(failedAgent)).isEmpty();
            assertThat(registry.register(successfulAgent, "Other agent").agentId())
                    .isEqualTo(successfulAgent);
        }
    }

    private static void assertFailureReason(
            ThrowingOperation operation,
            AgentRegistryException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(reason);
    }

    private enum FailurePoint {
        TEMPORARY_FORCE,
        BEFORE_PUBLICATION,
        MOVE,
        AFTER_PUBLICATION
    }

    private static final class FailingOperations extends AgentRegistryFileOperations {
        private final FailurePoint failurePoint;
        private boolean failed;

        private FailingOperations(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        @Override
        void beforeTemporaryForce(Path temporary) throws IOException {
            failAt(FailurePoint.TEMPORARY_FORCE);
        }

        @Override
        void beforePublication(Path temporary, Path target) throws IOException {
            failAt(FailurePoint.BEFORE_PUBLICATION);
        }

        @Override
        void moveIntoPlace(Path temporary, Path target) throws IOException {
            failAt(FailurePoint.MOVE);
            super.moveIntoPlace(temporary, target);
        }

        @Override
        void afterPublication(Path target) throws IOException {
            failAt(FailurePoint.AFTER_PUBLICATION);
        }

        private void failAt(FailurePoint point) throws IOException {
            if (!failed && failurePoint == point) {
                failed = true;
                throw new IOException("injected " + point);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws AgentRegistryException;
    }
}
