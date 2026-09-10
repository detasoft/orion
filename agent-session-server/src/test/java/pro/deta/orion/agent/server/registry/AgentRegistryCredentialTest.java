package pro.deta.orion.agent.server.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CONFLICT;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.INVALID_STATE;

class AgentRegistryCredentialTest {
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final AgentRecord.Credential PERMIT = credential(1, NOW.plusSeconds(60));
    private static final AgentRecord.Credential TOKEN = credential(2, NOW.plusSeconds(600));

    @TempDir
    Path root;

    @Test
    void consumedPermitAndRenewedTokenSurviveRestart() throws Exception {
        AgentRecord consumed;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            consumed = consume(registry, launch, NOW);
            assertThat(consumed.launch().orElseThrow().launchPermit()).isEmpty();
            assertThat(consumed.launch().orElseThrow().reconnectToken()).contains(TOKEN);
            assertThat(consumed.launch().orElseThrow().state()).isEqualTo(launch.state());
            assertThat(consumed.displayName()).isEqualTo("Build agent");
            assertFailure(() -> consume(registry, launch, NOW), INVALID_STATE);
        }
        AgentRecord renewed;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = consumed.launch().orElseThrow();
            assertThat(verify(registry, launch, NOW)).isEqualTo(consumed);
            renewed = registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                    TOKEN.digest(), NOW.plusSeconds(900), NOW);
            assertThat(renewed.launch().orElseThrow().reconnectToken())
                    .contains(new AgentRecord.Credential(TOKEN.digest(), NOW.plusSeconds(900)));
            assertThat(registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                    TOKEN.digest(), NOW.plusSeconds(700), NOW)).isEqualTo(renewed);
        }
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(verify(registry, renewed.launch().orElseThrow(), NOW.plusSeconds(899)))
                    .isEqualTo(renewed);
        }
    }

    @Test
    void rejectsExpiredOrWrongPermitAndInvalidTokenDeadlineWithoutConsuming() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            assertFailure(() -> registry.consumeLaunchPermit(AGENT, launch.generation(), launch.launchId(),
                    TOKEN.digest(), TOKEN, NOW), CONFLICT);
            for (Instant time : List.of(PERMIT.expiresAt(), PERMIT.expiresAt().plusNanos(1))) {
                assertFailure(() -> consume(registry, launch, time), INVALID_STATE);
            }
            for (Instant expiry : List.of(NOW, NOW.minusNanos(1))) {
                assertFailure(() -> registry.consumeLaunchPermit(AGENT, launch.generation(), launch.launchId(),
                        PERMIT.digest(), credential(2, expiry), NOW), INVALID_STATE);
            }
            assertThat(registry.find(AGENT).orElseThrow().launch()).contains(launch);
            assertThat(consume(registry, launch, PERMIT.expiresAt().minusNanos(1))
                    .launch().orElseThrow().reconnectToken()).contains(TOKEN);
        }
    }

    @Test
    void verificationAndRenewalRejectExpiredOrWrongToken() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            assertFailure(() -> verify(registry, launch, NOW), INVALID_STATE);
            AgentRecord consumed = consume(registry, launch, NOW);
            assertThat(verify(registry, launch, TOKEN.expiresAt().minusNanos(1))).isEqualTo(consumed);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, launch.generation(), launch.launchId(),
                    PERMIT.digest(), NOW), CONFLICT);
            assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                    PERMIT.digest(), NOW.plusSeconds(900), NOW), CONFLICT);
            for (Instant time : List.of(TOKEN.expiresAt(), TOKEN.expiresAt().plusNanos(1))) {
                assertFailure(() -> verify(registry, launch, time), INVALID_STATE);
                assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                        TOKEN.digest(), NOW.plusSeconds(900), time), INVALID_STATE);
            }
            assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                    TOKEN.digest(), NOW, NOW), INVALID_STATE);
            assertThat(registry.find(AGENT)).contains(consumed);
        }
    }

    @Test
    void credentialsAreBoundToAgentGenerationAndLaunch() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            AgentId other = new AgentId("agent-2");
            registry.register(other, "Other agent");
            AgentRecord.Launch otherLaunch = registry.allocateLaunch(other).launch().orElseThrow();
            registry.installLaunchPermit(other, otherLaunch.generation(), otherLaunch.launchId(), PERMIT, NOW);
            assertFailure(() -> registry.consumeLaunchPermit(other, launch.generation(), launch.launchId(),
                    PERMIT.digest(), TOKEN, NOW), CONFLICT);
            List<AgentRecord.Launch> wrongIdentities = List.of(
                    new AgentRecord.Launch(new AgentGeneration(2), launch.launchId(), launch.state(),
                            Optional.empty(), Optional.empty()),
                    new AgentRecord.Launch(launch.generation(), new AgentLaunchId(UUID.randomUUID()),
                            launch.state(), Optional.empty(), Optional.empty()));
            for (AgentRecord.Launch wrong : wrongIdentities) {
                assertFailure(() -> consume(registry, wrong, NOW), CONFLICT);
            }
            consume(registry, launch, NOW);
            for (AgentRecord.Launch wrong : wrongIdentities) {
                assertFailure(() -> verify(registry, wrong, NOW), CONFLICT);
                assertFailure(() -> registry.renewReconnectToken(AGENT, wrong.generation(), wrong.launchId(),
                        TOKEN.digest(), NOW.plusSeconds(900), NOW), CONFLICT);
            }
            AgentRecord replacement = registry.allocateLaunch(AGENT);
            assertFailure(() -> consume(registry, launch, NOW), CONFLICT);
            assertFailure(() -> verify(registry, launch, NOW), CONFLICT);
            assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                    TOKEN.digest(), NOW.plusSeconds(900), NOW), CONFLICT);
            assertThat(registry.find(AGENT)).contains(replacement);
        }
    }

    @Test
    void concurrentConsumptionCommitsExactlyOneToken() throws Exception {
        List<AgentRecord> successes = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRecord.Launch launch = prepare(registry);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<AgentRecord>>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                AgentRecord.Credential token = credential(index + 2, TOKEN.expiresAt());
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        return Optional.of(registry.consumeLaunchPermit(AGENT, launch.generation(),
                                launch.launchId(), PERMIT.digest(), token, NOW));
                    } catch (AgentRegistryException failure) {
                        assertThat(failure.reason()).isEqualTo(INVALID_STATE);
                        return Optional.empty();
                    }
                }));
            }
            start.countDown();
            for (Future<Optional<AgentRecord>> future : futures) {
                future.get(10, TimeUnit.SECONDS).ifPresent(successes::add);
            }
            assertThat(successes).hasSize(1);
        }
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(AGENT)).contains(successes.getFirst());
        }
    }

    @Test
    void concurrentRenewalsRetainLongestExpiry() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRecord.Launch launch = prepare(registry);
            consume(registry, launch, NOW);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AgentRecord>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                Instant expiry = NOW.plusSeconds(700 + index);
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                            TOKEN.digest(), expiry, NOW);
                }));
            }
            start.countDown();
            for (Future<AgentRecord> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).launch().orElseThrow().reconnectToken()).isPresent();
            }
        }
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(AGENT).orElseThrow().launch().orElseThrow().reconnectToken())
                    .contains(new AgentRecord.Credential(TOKEN.digest(), NOW.plusSeconds(707)));
        }
    }

    @Test
    void renewalRacingReplacementCannotRestoreOldCredential() throws Exception {
        AgentRecord replacement;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRecord.Launch launch = prepare(registry);
            consume(registry, launch, NOW);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> renewal = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(),
                            TOKEN.digest(), NOW.plusSeconds(900), NOW);
                } catch (AgentRegistryException failure) {
                    assertThat(failure.reason()).isEqualTo(CONFLICT);
                }
                return null;
            });
            Future<AgentRecord> allocation = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return registry.allocateLaunch(AGENT);
            });
            start.countDown();
            renewal.get(10, TimeUnit.SECONDS);
            replacement = allocation.get(10, TimeUnit.SECONDS);
            assertThat(registry.find(AGENT)).contains(replacement);
            assertThat(replacement.launch().orElseThrow().reconnectToken()).isEmpty();
            assertFailure(() -> verify(registry, launch, NOW), CONFLICT);
        }
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(AGENT)).contains(replacement);
        }
    }

    @Test
    void operationsRequireRegisteredLaunchedAndOpenOwner() throws Exception {
        AgentGeneration generation = new AgentGeneration(1);
        AgentLaunchId launchId = new AgentLaunchId(UUID.randomUUID());
        FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
        try (registry) {
            assertFailure(() -> registry.consumeLaunchPermit(AGENT, generation, launchId,
                    PERMIT.digest(), TOKEN, NOW), AgentRegistryException.Reason.NOT_FOUND);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, generation, launchId,
                    TOKEN.digest(), NOW), AgentRegistryException.Reason.NOT_FOUND);
            assertFailure(() -> registry.renewReconnectToken(AGENT, generation, launchId,
                    TOKEN.digest(), TOKEN.expiresAt(), NOW), AgentRegistryException.Reason.NOT_FOUND);
            registry.register(AGENT, "Build agent");
            assertFailure(() -> registry.consumeLaunchPermit(AGENT, generation, launchId,
                    PERMIT.digest(), TOKEN, NOW), INVALID_STATE);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, generation, launchId,
                    TOKEN.digest(), NOW), INVALID_STATE);
            assertFailure(() -> registry.renewReconnectToken(AGENT, generation, launchId,
                    TOKEN.digest(), TOKEN.expiresAt(), NOW), INVALID_STATE);
        }
        assertFailure(() -> registry.consumeLaunchPermit(AGENT, generation, launchId,
                PERMIT.digest(), TOKEN, NOW), AgentRegistryException.Reason.CLOSED);
        assertFailure(() -> registry.verifyReconnectToken(AGENT, generation, launchId,
                TOKEN.digest(), NOW), AgentRegistryException.Reason.CLOSED);
        assertFailure(() -> registry.renewReconnectToken(AGENT, generation, launchId,
                TOKEN.digest(), TOKEN.expiresAt(), NOW), AgentRegistryException.Reason.CLOSED);
    }

    private static AgentRecord.Launch prepare(FileSystemAgentRegistry registry) throws AgentRegistryException {
        registry.register(AGENT, "Build agent");
        AgentRecord.Launch launch = registry.allocateLaunch(AGENT).launch().orElseThrow();
        return registry.installLaunchPermit(AGENT, launch.generation(), launch.launchId(), PERMIT, NOW)
                .launch().orElseThrow();
    }

    private static AgentRecord consume(FileSystemAgentRegistry registry, AgentRecord.Launch launch, Instant now)
            throws AgentRegistryException {
        return registry.consumeLaunchPermit(AGENT, launch.generation(), launch.launchId(), PERMIT.digest(),
                TOKEN, now);
    }

    private static AgentRecord verify(FileSystemAgentRegistry registry, AgentRecord.Launch launch, Instant now)
            throws AgentRegistryException {
        return registry.verifyReconnectToken(AGENT, launch.generation(), launch.launchId(), TOKEN.digest(), now);
    }

    private static AgentRecord.Credential credential(int value, Instant expiry) {
        byte[] bytes = new byte[AgentRecord.CredentialDigest.SIZE_BYTES];
        bytes[0] = (byte) value;
        return new AgentRecord.Credential(new AgentRecord.CredentialDigest(bytes), expiry);
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            AgentRegistryException.Reason reason) {
        assertThatThrownBy(operation).isInstanceOf(AgentRegistryException.class)
                .extracting(failure -> ((AgentRegistryException) failure).reason()).isEqualTo(reason);
    }
}
