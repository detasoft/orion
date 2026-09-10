package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryDurabilityTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

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

    @Test
    void allocationFailuresNeverReturnUncommittedLaunchMaterial() throws AgentRegistryException {
        for (FailurePoint point : FailurePoint.values()) {
            assertLaunchPublicationFailure(point, false);
        }
    }

    @Test
    void permitFailuresNeverReturnUncommittedPermitMaterial() throws AgentRegistryException {
        for (FailurePoint point : FailurePoint.values()) {
            assertLaunchPublicationFailure(point, true);
        }
    }

    private void assertLaunchPublicationFailure(FailurePoint point, boolean installingPermit)
            throws AgentRegistryException {
        Path root = temporaryDirectory.resolve(point.name());
        AgentId agentId = new AgentId("agent-1");
        AgentRecord before;
        AgentRecord.Credential permit = new AgentRecord.Credential(
                new AgentRecord.CredentialDigest(new byte[32]), NOW.plusSeconds(60));
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(agentId, "Build agent");
            before = registry.allocateLaunch(agentId);
            if (!installingPermit) {
                AgentRecord.Launch launch = before.launch().orElseThrow();
                before = registry.installLaunchPermit(
                        agentId, launch.generation(), launch.launchId(), permit, NOW);
            }
        }
        AgentRecord.Launch previous = before.launch().orElseThrow();
        boolean indeterminate = point == FailurePoint.MOVE || point == FailurePoint.AFTER_PUBLICATION;
        AgentRegistryException.Reason reason = indeterminate
                ? AgentRegistryException.Reason.INDETERMINATE : AgentRegistryException.Reason.IO_FAILURE;
        try (FileSystemAgentRegistry registry = FileSystemAgentRegistry.withOperations(
                root, new FailingOperations(point))) {
            ThrowingOperation mutation = installingPermit
                    ? () -> registry.installLaunchPermit(
                            agentId, previous.generation(), previous.launchId(), permit, NOW)
                    : () -> registry.allocateLaunch(agentId);
            assertFailureReason(mutation, reason);
            if (indeterminate) {
                assertFailureReason(() -> registry.find(agentId), reason);
                assertFailureReason(() -> registry.allocateLaunch(agentId), reason);
                assertFailureReason(() -> registry.installLaunchPermit(
                        agentId, previous.generation(), previous.launchId(), permit, NOW), reason);
                assertFailureReason(() -> registry.register(new AgentId("agent-2"), "Other agent"), reason);
            } else {
                assertThat(registry.find(agentId)).contains(before);
            }
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            AgentRecord recovered = reopened.find(agentId).orElseThrow();
            AgentRecord.Launch launch = recovered.launch().orElseThrow();
            if (point == FailurePoint.AFTER_PUBLICATION) {
                if (installingPermit) {
                    assertThat(launch).isEqualTo(new AgentRecord.Launch(
                            previous.generation(), previous.launchId(), AgentRecord.LaunchState.STARTING,
                            Optional.of(permit), Optional.empty()));
                } else {
                    assertThat(launch.generation().value()).isEqualTo(previous.generation().value() + 1);
                    assertThat(launch.launchId()).isNotEqualTo(previous.launchId());
                    assertThat(launch.state()).isEqualTo(AgentRecord.LaunchState.RECOVERING);
                    assertThat(launch.launchPermit()).isEmpty();
                    assertThat(launch.reconnectToken()).isEmpty();
                }
            } else {
                assertThat(recovered).isEqualTo(before);
            }
            AgentRecord.Launch next = reopened.allocateLaunch(agentId).launch().orElseThrow();
            assertThat(next.generation().value()).isEqualTo(launch.generation().value() + 1);
            assertThat(next.launchPermit()).isEmpty();
            assertThat(next.reconnectToken()).isEmpty();
        }
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
