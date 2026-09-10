package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.MachineInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.agent.server.registry.AgentRecord.LaunchState.ONLINE;
import static pro.deta.orion.agent.server.registry.AgentRecord.LaunchState.RECOVERING;
import static pro.deta.orion.agent.server.registry.AgentRecord.LaunchState.STARTING;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CONFLICT;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.INVALID_STATE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.NOT_FOUND;

class AgentRegistryLaunchTest {
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @TempDir
    Path root;

    @Test
    void firstLaunchStartsAtOneAndSurvivesRestart() throws Exception {
        AgentRecord allocated;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT, "Build agent");
            AgentRecord other = registry.register(new AgentId("agent-2"), "Other agent");

            allocated = registry.allocateLaunch(AGENT);

            assertThat(allocated.launch()).isPresent();
            AgentRecord.Launch launch = allocated.launch().orElseThrow();
            assertThat(launch.generation()).isEqualTo(new AgentGeneration(1));
            assertThat(launch.launchId()).isNotNull();
            assertThat(launch.state()).isEqualTo(RECOVERING);
            assertThat(launch.launchPermit()).isEmpty();
            assertThat(launch.reconnectToken()).isEmpty();
            assertThat(registry.find(AGENT)).contains(allocated);
            assertThat(registry.register(AGENT, "Build agent")).isEqualTo(allocated);
            assertThat(registry.find(other.agentId())).contains(other);
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(allocated);
            AgentRecord.Launch next = reopened.allocateLaunch(AGENT).launch().orElseThrow();
            assertThat(next.generation()).isEqualTo(new AgentGeneration(2));
            assertThat(next.launchId()).isNotEqualTo(allocated.launch().orElseThrow().launchId());
        }
    }

    @Test
    void replacementRevokesPermitAndRetainsHistoricalObservation() throws Exception {
        assertReplacementRevokesCredential(false);
    }

    @Test
    void replacementRevokesReconnectTokenAndRetainsHistoricalObservation() throws Exception {
        assertReplacementRevokesCredential(true);
    }

    @Test
    void concurrentAllocationsDoNotLoseOrReuseGenerations() throws Exception {
        int count = 24;
        Set<Long> generations = new HashSet<>();
        Set<AgentLaunchId> launchIds = new HashSet<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            registry.register(AGENT, "Build agent");
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AgentRecord>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return registry.allocateLaunch(AGENT);
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (Future<AgentRecord> future : futures) {
                AgentRecord allocated = future.get(10, TimeUnit.SECONDS);
                assertThat(allocated.launch()).isPresent();
                AgentRecord.Launch launch = allocated.launch().orElseThrow();
                assertThat(generations.add(launch.generation().value())).isTrue();
                assertThat(launchIds.add(launch.launchId())).isTrue();
            }
        }
        assertThat(generations).hasSize(count);
        for (long generation = 1; generation <= count; generation++) {
            assertThat(generations).contains(generation);
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT).orElseThrow().launch().orElseThrow().generation())
                    .isEqualTo(new AgentGeneration(count));
            assertThat(reopened.allocateLaunch(AGENT).launch().orElseThrow().generation())
                    .isEqualTo(new AgentGeneration(count + 1));
        }
    }

    @Test
    void overflowLeavesTheExistingLaunchAndCredentialsIntact() throws Exception {
        AgentRecord existing = storedRecord(Long.MAX_VALUE, true);
        writeRecord(existing);
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertFailureReason(() -> registry.allocateLaunch(AGENT), INVALID_STATE);
            assertThat(registry.find(AGENT)).contains(existing);
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(existing);
        }
    }

    @Test
    void allocationRequiresARegisteredAgent() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertFailureReason(() -> registry.allocateLaunch(AGENT), NOT_FOUND);
            assertThat(registry.find(AGENT)).isEmpty();
        }
    }

    @Test
    void permitInstallationStartsCurrentLaunchAndSurvivesRestart() throws Exception {
        AgentRecord previous = storedRecord(7, true);
        writeRecord(previous);
        AgentRecord starting;
        AgentRecord.Credential permit = permit(NOW.plusSeconds(60));
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch recovering = registry.allocateLaunch(AGENT).launch().orElseThrow();
            starting = registry.installLaunchPermit(
                    AGENT, recovering.generation(), recovering.launchId(), permit, NOW);

            assertThat(starting.launch()).contains(new AgentRecord.Launch(
                    recovering.generation(), recovering.launchId(), STARTING,
                    Optional.of(permit), Optional.empty()));
            assertThat(starting.observation()).isEqualTo(previous.observation());
            assertThat(starting.displayName()).isEqualTo(previous.displayName());
            assertThat(registry.find(AGENT)).contains(starting);
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(starting);
            AgentRecord.Launch replacement = reopened.allocateLaunch(AGENT).launch().orElseThrow();
            assertThat(replacement.generation()).isEqualTo(new AgentGeneration(9));
            assertThat(replacement.launchPermit()).isEmpty();
            assertThat(replacement.reconnectToken()).isEmpty();
        }
    }

    @Test
    void staleGenerationOrLaunchIdCannotInstallPermitForReplacement() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT, "Build agent");
            AgentRecord.Launch old = registry.allocateLaunch(AGENT).launch().orElseThrow();
            AgentRecord replacement = registry.allocateLaunch(AGENT);
            AgentRecord.Launch current = replacement.launch().orElseThrow();
            AgentRecord.Credential permit = permit(NOW.plusSeconds(60));

            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, old.generation(), old.launchId(), permit, NOW), CONFLICT);
            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, current.generation(), old.launchId(), permit, NOW), CONFLICT);
            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, old.generation(), current.launchId(), permit, NOW), CONFLICT);
            assertThat(registry.find(AGENT)).contains(replacement);
        }
    }

    @Test
    void permitRequiresAnUnexpiredDeadline() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT, "Build agent");
            AgentRecord allocated = registry.allocateLaunch(AGENT);
            AgentRecord.Launch launch = allocated.launch().orElseThrow();
            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, launch.generation(), launch.launchId(), permit(NOW), NOW), INVALID_STATE);
            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, launch.generation(), launch.launchId(), permit(NOW.minusNanos(1)), NOW),
                    INVALID_STATE);
            assertThat(registry.find(AGENT)).contains(allocated);

            AgentRecord.Credential valid = permit(NOW.plusNanos(1));
            assertThat(registry.installLaunchPermit(
                    AGENT, launch.generation(), launch.launchId(), valid, NOW)
                    .launch().orElseThrow().launchPermit()).contains(valid);
        }
    }

    @Test
    void permitInstallationRequiresARegisteredCurrentLaunch() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentGeneration generation = new AgentGeneration(1);
            AgentLaunchId launchId = new AgentLaunchId(UUID.randomUUID());
            AgentRecord.Credential permit = permit(NOW.plusSeconds(60));
            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, generation, launchId, permit, NOW), NOT_FOUND);
            AgentRecord registered = registry.register(AGENT, "Build agent");
            assertFailureReason(() -> registry.installLaunchPermit(
                    AGENT, generation, launchId, permit, NOW), INVALID_STATE);
            assertThat(registry.find(AGENT)).contains(registered);
        }
    }

    @Test
    void permitInstallationRequiresRecoveringStateWithoutExistingCredentials() throws Exception {
        AgentRecord previous = storedRecord(7, true);
        AgentRecord.Launch identity = previous.launch().orElseThrow();
        AgentRecord.Credential permit = permit(NOW.plusSeconds(60));
        for (AgentRecord.LaunchState state : AgentRecord.LaunchState.values()) {
            for (int credentialKind = 0; credentialKind < 3; credentialKind++) {
                if (state == RECOVERING && credentialKind == 0) {
                    continue;
                }
                AgentRecord existing = new AgentRecord(AGENT, previous.displayName(),
                        Optional.of(new AgentRecord.Launch(identity.generation(), identity.launchId(), state,
                                credentialKind == 1 ? Optional.of(permit) : Optional.empty(),
                                credentialKind == 2 ? Optional.of(permit) : Optional.empty())),
                        previous.observation());
                writeRecord(existing);
                try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
                    assertFailureReason(() -> registry.installLaunchPermit(
                            AGENT, identity.generation(), identity.launchId(), permit, NOW), INVALID_STATE);
                    assertThat(registry.find(AGENT)).contains(existing);
                }
            }
        }
    }

    @Test
    void competingInstallationsCommitExactlyOnePermit() throws Exception {
        Set<AgentRecord.Credential> committed = new HashSet<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            registry.register(AGENT, "Build agent");
            AgentRecord.Launch launch = registry.allocateLaunch(AGENT).launch().orElseThrow();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<AgentRecord>>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                byte[] digest = new byte[AgentRecord.CredentialDigest.SIZE_BYTES];
                digest[0] = (byte) index;
                AgentRecord.Credential permit = new AgentRecord.Credential(
                        new AgentRecord.CredentialDigest(digest), NOW.plusSeconds(60));
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        return Optional.of(registry.installLaunchPermit(
                                AGENT, launch.generation(), launch.launchId(), permit, NOW));
                    } catch (AgentRegistryException failure) {
                        assertThat(failure.reason()).isEqualTo(INVALID_STATE);
                        return Optional.empty();
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Optional<AgentRecord>> future : futures) {
                Optional<AgentRecord> result = future.get(10, TimeUnit.SECONDS);
                if (result.isPresent()) {
                    successes++;
                    assertThat(result.get().launch().orElseThrow().launchPermit()).isPresent();
                    committed.add(result.get().launch().orElseThrow().launchPermit().orElseThrow());
                }
            }
            assertThat(successes).isEqualTo(1);
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(committed).containsExactly(
                    reopened.find(AGENT).orElseThrow().launch().orElseThrow().launchPermit().orElseThrow());
        }
    }

    private void assertReplacementRevokesCredential(boolean reconnect) throws Exception {
        AgentRecord previous = storedRecord(7, reconnect);
        writeRecord(previous);
        AgentRecord replacement;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            replacement = registry.allocateLaunch(AGENT);
            AgentRecord.Launch launch = replacement.launch().orElseThrow();
            assertThat(launch.generation()).isEqualTo(new AgentGeneration(8));
            assertThat(launch.launchId()).isNotEqualTo(previous.launch().orElseThrow().launchId());
            assertThat(launch.state()).isEqualTo(RECOVERING);
            assertThat(launch.launchPermit()).isEmpty();
            assertThat(launch.reconnectToken()).isEmpty();
            assertThat(replacement.observation()).isEqualTo(previous.observation());
            assertThat(replacement.displayName()).isEqualTo(previous.displayName());
        }
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(replacement);
        }
    }

    private AgentRecord storedRecord(long generation, boolean reconnect) {
        AgentGeneration currentGeneration = new AgentGeneration(generation);
        AgentLaunchId launchId = new AgentLaunchId(UUID.randomUUID());
        AgentRecord.Launch launch = new AgentRecord.Launch(
                currentGeneration, launchId, reconnect ? ONLINE : STARTING,
                reconnect ? Optional.empty() : Optional.of(permit(NOW.plusSeconds(60))),
                reconnect ? Optional.of(permit(NOW.plusSeconds(600))) : Optional.empty());
        AgentRecord.Observation observation = new AgentRecord.Observation(
                currentGeneration, launchId, new AgentInstanceId(UUID.randomUUID()), "1.2.3",
                new MachineInfo("worker-1", "linux", "aarch64"), Map.of("pty", "true"), NOW);
        return new AgentRecord(AGENT, "Build agent", Optional.of(launch), Optional.of(observation));
    }

    private void writeRecord(AgentRecord record) throws Exception {
        Files.write(root.resolve(AgentRecordCodec.fileName(record.agentId())),
                new AgentRecordCodec().encode(record));
    }

    private static AgentRecord.Credential permit(Instant expiresAt) {
        return new AgentRecord.Credential(
                new AgentRecord.CredentialDigest(new byte[AgentRecord.CredentialDigest.SIZE_BYTES]), expiresAt);
    }

    private static void assertFailureReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            AgentRegistryException.Reason reason) {
        assertThatThrownBy(operation)
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(reason);
    }
}
