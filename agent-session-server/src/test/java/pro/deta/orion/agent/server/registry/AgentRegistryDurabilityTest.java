package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.MachineInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    @Test
    void credentialFailuresNeverReportAnUncommittedTokenOrRenewal() throws AgentRegistryException {
        AgentId agentId = new AgentId("agent-1");
        AgentRecord.Credential permit = new AgentRecord.Credential(
                new AgentRecord.CredentialDigest(new byte[32]), NOW.plusSeconds(60));
        byte[] tokenBytes = new byte[32];
        tokenBytes[0] = 1;
        AgentRecord.Credential token = new AgentRecord.Credential(
                new AgentRecord.CredentialDigest(tokenBytes), NOW.plusSeconds(600));
        Instant renewedExpiry = NOW.plusSeconds(900);
        for (boolean renewal : new boolean[]{false, true}) {
            for (FailurePoint point : FailurePoint.values()) {
                Path root = temporaryDirectory.resolve(point.name() + renewal);
                AgentRecord before;
                try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
                    registry.register(agentId, "Build agent");
                    AgentRecord.Launch launch = registry.allocateLaunch(agentId).launch().orElseThrow();
                    before = registry.installLaunchPermit(
                            agentId, launch.generation(), launch.launchId(), permit, NOW);
                    if (renewal) {
                        before = registry.consumeLaunchPermit(agentId, launch.generation(), launch.launchId(),
                                permit.digest(), token, NOW);
                    }
                }
                AgentRecord.Launch launch = before.launch().orElseThrow();
                boolean indeterminate = point == FailurePoint.MOVE || point == FailurePoint.AFTER_PUBLICATION;
                AgentRegistryException.Reason reason = indeterminate
                        ? AgentRegistryException.Reason.INDETERMINATE : AgentRegistryException.Reason.IO_FAILURE;
                try (FileSystemAgentRegistry registry = FileSystemAgentRegistry.withOperations(
                        root, new FailingOperations(point))) {
                    ThrowingOperation mutation = renewal
                            ? () -> registry.renewReconnectToken(agentId, launch.generation(), launch.launchId(),
                                    token.digest(), renewedExpiry, NOW)
                            : () -> registry.consumeLaunchPermit(agentId, launch.generation(), launch.launchId(),
                                    permit.digest(), token, NOW);
                    assertFailureReason(mutation, reason);
                    if (indeterminate) {
                        assertFailureReason(() -> registry.find(agentId), reason);
                        assertFailureReason(() -> registry.verifyReconnectToken(
                                agentId, launch.generation(), launch.launchId(), token.digest(), NOW), reason);
                        assertFailureReason(mutation, reason);
                    } else {
                        assertThat(registry.find(agentId)).contains(before);
                        if (!renewal) {
                            assertFailureReason(() -> registry.verifyReconnectToken(
                                    agentId, launch.generation(), launch.launchId(), token.digest(), NOW),
                                    AgentRegistryException.Reason.INVALID_STATE);
                        }
                    }
                }
                try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
                    if (point == FailurePoint.AFTER_PUBLICATION) {
                        AgentRecord recovered = registry.verifyReconnectToken(
                                agentId, launch.generation(), launch.launchId(), token.digest(), NOW);
                        assertThat(recovered.launch().orElseThrow().launchPermit()).isEmpty();
                        assertThat(recovered.launch().orElseThrow().reconnectToken()).contains(
                                renewal ? new AgentRecord.Credential(token.digest(), renewedExpiry) : token);
                    } else {
                        assertThat(registry.find(agentId)).contains(before);
                    }
                }
            }
        }
    }

    @Test
    void observationFailuresNeverReportUncommittedMetadata() throws AgentRegistryException {
        AgentId agentId = new AgentId("agent-1");
        for (FailurePoint point : FailurePoint.values()) {
            Path root = temporaryDirectory.resolve("observation-" + point);
            AgentRecord before;
            try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
                registry.register(agentId, "Build agent");
                before = registry.allocateLaunch(agentId);
            }
            AgentRecord.Launch launch = before.launch().orElseThrow();
            AgentRecord.Observation observation = new AgentRecord.Observation(
                    launch.generation(),
                    launch.launchId(),
                    new AgentInstanceId(UUID.fromString("15caeaf0-402d-40aa-8205-ed61cb31c41b")),
                    "1.2.3",
                    new MachineInfo("worker-1", "linux", "aarch64"),
                    Map.of("pty", "true"),
                    NOW);
            boolean indeterminate = point == FailurePoint.MOVE || point == FailurePoint.AFTER_PUBLICATION;
            AgentRegistryException.Reason reason = indeterminate
                    ? AgentRegistryException.Reason.INDETERMINATE : AgentRegistryException.Reason.IO_FAILURE;
            try (FileSystemAgentRegistry registry = FileSystemAgentRegistry.withOperations(
                    root, new FailingOperations(point))) {
                assertFailureReason(() -> registry.recordObservation(agentId, observation), reason);
                if (indeterminate) {
                    assertFailureReason(() -> registry.find(agentId), reason);
                    assertFailureReason(() -> registry.recordObservation(agentId, observation), reason);
                } else {
                    assertThat(registry.find(agentId)).contains(before);
                }
            }
            try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
                AgentRecord recovered = reopened.find(agentId).orElseThrow();
                if (point == FailurePoint.AFTER_PUBLICATION) {
                    assertThat(recovered.observation()).contains(observation);
                } else {
                    assertThat(recovered).isEqualTo(before);
                }
            }
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
