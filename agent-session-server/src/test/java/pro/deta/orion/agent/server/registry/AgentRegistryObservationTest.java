package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.MachineInfo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.agent.server.registry.AgentRecord.LaunchState.RECOVERING;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CONFLICT;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.INVALID_STATE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.NOT_FOUND;

class AgentRegistryObservationTest {
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @TempDir
    Path root;

    @Test
    void initialAndReplacementObservationsSurviveRestartWithoutChangingLaunchState() throws Exception {
        AgentRecord firstObserved;
        AgentRecord.Observation first;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            registry.register(AGENT, "Build agent");
            AgentRecord.Launch launch = registry.allocateLaunch(AGENT).launch().orElseThrow();
            first = observation(launch, 1, NOW);

            firstObserved = registry.recordObservation(AGENT, first);

            assertThat(firstObserved.launch()).contains(launch);
            assertThat(firstObserved.observation()).contains(first);
            assertThat(firstObserved.launch().orElseThrow().state()).isEqualTo(RECOVERING);
        }

        AgentRecord replacementObserved;
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(firstObserved);
            AgentRecord replacement = reopened.allocateLaunch(AGENT);
            assertThat(replacement.observation()).contains(first);
            AgentRecord.Launch launch = replacement.launch().orElseThrow();
            AgentRecord.Observation second = observation(launch, 2, NOW.plusSeconds(1));

            replacementObserved = reopened.recordObservation(AGENT, second);

            assertThat(replacementObserved.launch()).contains(launch);
            assertThat(replacementObserved.observation()).contains(second);
            assertThat(replacementObserved.launch().orElseThrow().state()).isEqualTo(RECOVERING);
        }

        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(replacementObserved);
            assertThat(reopened.find(AGENT).orElseThrow().launch().orElseThrow().state())
                    .isEqualTo(RECOVERING);
        }
    }

    @Test
    void observationRequiresARegisteredCurrentLaunch() throws Exception {
        AgentGeneration generation = new AgentGeneration(1);
        AgentLaunchId launchId = new AgentLaunchId(UUID.randomUUID());
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertFailure(() -> registry.recordObservation(
                    AGENT, observation(generation, launchId, 1, NOW)), NOT_FOUND);
            AgentRecord registered = registry.register(AGENT, "Build agent");
            assertFailure(() -> registry.recordObservation(
                    AGENT, observation(generation, launchId, 1, NOW)), INVALID_STATE);
            assertThat(registry.find(AGENT)).contains(registered);

            AgentRecord.Launch first = registry.allocateLaunch(AGENT).launch().orElseThrow();
            assertFailure(() -> registry.recordObservation(AGENT, observation(
                    new AgentGeneration(2), first.launchId(), 1, NOW)), CONFLICT);
            assertFailure(() -> registry.recordObservation(AGENT, observation(
                    first.generation(), new AgentLaunchId(UUID.randomUUID()), 1, NOW)), CONFLICT);
            AgentRecord.Observation historical = observation(first, 1, NOW);
            registry.recordObservation(AGENT, historical);

            AgentRecord replacement = registry.allocateLaunch(AGENT);
            assertFailure(() -> registry.recordObservation(AGENT, observation(first, 2, NOW.plusSeconds(1))),
                    CONFLICT);
            assertThat(registry.find(AGENT)).contains(replacement);
            assertThat(replacement.observation()).contains(historical);
        }
    }

    @Test
    void concurrentUpdatesPublishOneCompleteObservation() throws Exception {
        List<AgentRecord.Observation> candidates = new ArrayList<>();
        AgentRecord committed;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            registry.register(AGENT, "Build agent");
            AgentRecord.Launch launch = registry.allocateLaunch(AGENT).launch().orElseThrow();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AgentRecord>> futures = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                AgentRecord.Observation candidate = observation(launch, index, NOW.plusSeconds(index));
                candidates.add(candidate);
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return registry.recordObservation(AGENT, candidate);
                }));
            }

            start.countDown();
            for (int index = 0; index < futures.size(); index++) {
                assertThat(futures.get(index).get(10, TimeUnit.SECONDS).observation())
                        .contains(candidates.get(index));
            }
            committed = registry.find(AGENT).orElseThrow();
            assertThat(candidates).contains(committed.observation().orElseThrow());
            assertThat(committed.launch()).contains(launch);
        }

        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            assertThat(reopened.find(AGENT)).contains(committed);
        }
    }

    private static AgentRecord.Observation observation(
            AgentRecord.Launch launch,
            int sequence,
            Instant observedAt) {
        return observation(launch.generation(), launch.launchId(), sequence, observedAt);
    }

    private static AgentRecord.Observation observation(
            AgentGeneration generation,
            AgentLaunchId launchId,
            int sequence,
            Instant observedAt) {
        return new AgentRecord.Observation(
                generation,
                launchId,
                new AgentInstanceId(new UUID(0, sequence + 1L)),
                "1.2." + sequence,
                new MachineInfo("worker-" + sequence, "linux", "aarch64"),
                Map.of("sequence", Integer.toString(sequence)),
                observedAt);
    }

    private static void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            AgentRegistryException.Reason reason) {
        assertThatThrownBy(operation)
                .isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason())
                .isEqualTo(reason);
    }
}
