package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentInstanceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLabel;
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
    private static final AgentInstanceId INSTANCE =
            new AgentInstanceId(new UUID(0, 1));
    private static final AgentLabel AGENT = new AgentLabel("agent-1");
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final AgentRecord.Credential PERMIT = credential(1, NOW.plusSeconds(60));
    private static final AgentRecord.Credential TOKEN = credential(2, NOW.plusSeconds(600));

    @TempDir
    Path root;

    @Test
    void occupiedLabelRequiresExplicitExpectedInstanceAndRejectsStaleRestartAuthorization() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch first = prepare(registry);
            consume(registry, first, NOW);
            assertFailure(() -> registry.allocateLaunch(AGENT, Optional.empty()), CONFLICT);
            AgentRecord replacement = replace(registry);
            assertFailure(() -> registry.allocateLaunch(AGENT, Optional.of(INSTANCE)), CONFLICT);
            assertThat(registry.find(AGENT)).contains(replacement);
        }
    }

    @Test
    void reconnectCredentialCannotAuthenticateAnotherProcessOrLabel() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch first = prepare(registry);
            consume(registry, first, NOW);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, first.generation(), first.launchId(),
                    new AgentInstanceId(new UUID(0, 2)), TOKEN.digest(), NOW), CONFLICT);
            AgentLabel other = new AgentLabel("agent-2");
            registry.register(other, "Other");
            assertFailure(() -> registry.verifyReconnectToken(other, first.generation(), first.launchId(),
                    INSTANCE, TOKEN.digest(), NOW), INVALID_STATE);
            assertThat(verify(registry, first, NOW).registration().orElseThrow().instanceId()).isEqualTo(INSTANCE);
        }
    }

    @Test
    void pendingReplacementAndCurrentAuthoritySurviveServerRestart() throws Exception {
        AgentRecord.Launch first;
        AgentRecord.Launch pending;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            first = prepare(registry);
            consume(registry, first, NOW);
            pending = registry.allocateLaunch(AGENT, Optional.of(INSTANCE)).launch().orElseThrow();
            registry.installLaunchPermit(AGENT, pending.generation(), pending.launchId(), PERMIT, NOW);
        }
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            verify(registry, first, NOW);
            registry.consumeLaunchPermit(AGENT, pending.generation(), pending.launchId(),
                    new AgentInstanceId(new UUID(0, 2)), PERMIT.digest(), TOKEN, NOW);
            assertFailure(() -> verify(registry, first, NOW), CONFLICT);
        }
    }

    @Test
    void replacementDrainsConcurrentOperationsAndFencesLaterOperations() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRecord.Launch first = prepare(registry);
            consume(registry, first, NOW);
            CountDownLatch replacementStarted = new CountDownLatch(1);
            Future<AgentRecord> replacement;
            try (var firstOperation = registry.acquireRegistration(
                    AGENT, first.generation(), first.launchId(), INSTANCE)) {
                Future<?> secondOperation = executor.submit(() -> {
                    try (var operation = registry.acquireRegistration(
                            AGENT, first.generation(), first.launchId(), INSTANCE)) {
                        return null;
                    }
                });
                secondOperation.get(5, TimeUnit.SECONDS);
                replacement = executor.submit(() -> {
                    replacementStarted.countDown();
                    return replace(registry);
                });
                assertThat(replacementStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> replacement.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            }
            replacement.get(5, TimeUnit.SECONDS);
            assertFailure(() -> registry.acquireRegistration(
                    AGENT, first.generation(), first.launchId(), INSTANCE), CONFLICT);
        }
    }

    @Test
    void pendingReplacementDoesNotRevokeCurrentReconnectAuthority() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            consume(registry, launch, NOW);
            registry.allocateLaunch(AGENT, registry.find(AGENT)
                    .flatMap(record -> record.registration().map(AgentRecord.Registration::instanceId)));
            verify(registry, launch, NOW);
        }
    }

    @Test
    void consumedPermitAndRenewedTokenSurviveRestart() throws Exception {
        AgentRecord consumed;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            consumed = consume(registry, launch, NOW);
            assertThat(consumed.launch().orElseThrow().launchPermit()).isEmpty();
            assertThat(consumed.registration().map(AgentRecord.Registration::reconnectToken)).contains(TOKEN);
            assertThat(consumed.launch().orElseThrow().state()).isEqualTo(launch.state());
            assertThat(consumed.displayName()).isEqualTo("Build agent");
            assertFailure(() -> consume(registry, launch, NOW), INVALID_STATE);
        }
        AgentRecord renewed;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = consumed.launch().orElseThrow();
            assertThat(verify(registry, launch, NOW)).isEqualTo(consumed);
            renewed = registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                    TOKEN.digest(), NOW.plusSeconds(900), NOW);
            assertThat(renewed.registration().map(AgentRecord.Registration::reconnectToken))
                    .contains(new AgentRecord.Credential(TOKEN.digest(), NOW.plusSeconds(900)));
            assertThat(registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
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
            assertFailure(() -> registry.consumeLaunchPermit(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                    TOKEN.digest(), TOKEN, NOW), CONFLICT);
            for (Instant time : List.of(PERMIT.expiresAt(), PERMIT.expiresAt().plusNanos(1))) {
                assertFailure(() -> consume(registry, launch, time), INVALID_STATE);
            }
            for (Instant expiry : List.of(NOW, NOW.minusNanos(1))) {
                assertFailure(() -> registry.consumeLaunchPermit(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                        PERMIT.digest(), credential(2, expiry), NOW), INVALID_STATE);
            }
            assertThat(registry.find(AGENT).orElseThrow().launch()).contains(launch);
            assertThat(consume(registry, launch, PERMIT.expiresAt().minusNanos(1))
                    .registration().map(AgentRecord.Registration::reconnectToken)).contains(TOKEN);
        }
    }

    @Test
    void verificationAndRenewalRejectExpiredOrWrongToken() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            assertFailure(() -> verify(registry, launch, NOW), INVALID_STATE);
            AgentRecord consumed = consume(registry, launch, NOW);
            assertThat(verify(registry, launch, TOKEN.expiresAt().minusNanos(1))).isEqualTo(consumed);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                    PERMIT.digest(), NOW), CONFLICT);
            assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                    PERMIT.digest(), NOW.plusSeconds(900), NOW), CONFLICT);
            for (Instant time : List.of(TOKEN.expiresAt(), TOKEN.expiresAt().plusNanos(1))) {
                assertFailure(() -> verify(registry, launch, time), INVALID_STATE);
                assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                        TOKEN.digest(), NOW.plusSeconds(900), time), INVALID_STATE);
            }
            assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                    TOKEN.digest(), NOW, NOW), INVALID_STATE);
            assertThat(registry.find(AGENT)).contains(consumed);
        }
    }

    @Test
    void credentialsAreBoundToAgentGenerationAndLaunch() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepare(registry);
            AgentLabel other = new AgentLabel("agent-2");
            registry.register(other, "Other agent");
            AgentRecord.Launch otherLaunch = registry.allocateLaunch(other, registry.find(other)
                    .flatMap(record -> record.registration().map(AgentRecord.Registration::instanceId))).launch().orElseThrow();
            registry.installLaunchPermit(other, otherLaunch.generation(), otherLaunch.launchId(), PERMIT, NOW);
            assertFailure(() -> registry.consumeLaunchPermit(other, launch.generation(), launch.launchId(), INSTANCE,
                    PERMIT.digest(), TOKEN, NOW), CONFLICT);
            List<AgentRecord.Launch> wrongIdentities = List.of(
                    new AgentRecord.Launch(new AgentGeneration(2), launch.launchId(), launch.state(),
                            Optional.empty()),
                    new AgentRecord.Launch(launch.generation(), new AgentLaunchId(UUID.randomUUID()),
                            launch.state(), Optional.empty()));
            for (AgentRecord.Launch wrong : wrongIdentities) {
                assertFailure(() -> consume(registry, wrong, NOW), CONFLICT);
            }
            consume(registry, launch, NOW);
            for (AgentRecord.Launch wrong : wrongIdentities) {
                assertFailure(() -> verify(registry, wrong, NOW), CONFLICT);
                assertFailure(() -> registry.renewReconnectToken(AGENT, wrong.generation(), wrong.launchId(), INSTANCE,
                        TOKEN.digest(), NOW.plusSeconds(900), NOW), CONFLICT);
            }
            AgentRecord replacement = replace(registry);
            assertFailure(() -> consume(registry, launch, NOW), CONFLICT);
            assertFailure(() -> verify(registry, launch, NOW), CONFLICT);
            assertFailure(() -> registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
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
                                launch.launchId(), INSTANCE, PERMIT.digest(), token, NOW));
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
                    return registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                            TOKEN.digest(), expiry, NOW);
                }));
            }
            start.countDown();
            for (Future<AgentRecord> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).registration().map(AgentRecord.Registration::reconnectToken)).isPresent();
            }
        }
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            assertThat(registry.find(AGENT).orElseThrow().registration().map(AgentRecord.Registration::reconnectToken))
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
                    registry.renewReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE,
                            TOKEN.digest(), NOW.plusSeconds(900), NOW);
                } catch (AgentRegistryException failure) {
                    assertThat(failure.reason()).isEqualTo(CONFLICT);
                }
                return null;
            });
            Future<AgentRecord> allocation = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return replace(registry);
            });
            start.countDown();
            renewal.get(10, TimeUnit.SECONDS);
            replacement = allocation.get(10, TimeUnit.SECONDS);
            assertThat(registry.find(AGENT)).contains(replacement);
            assertThat(replacement.registration().orElseThrow().instanceId()).isNotEqualTo(INSTANCE);
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
            assertFailure(() -> registry.consumeLaunchPermit(AGENT, generation, launchId, INSTANCE,
                    PERMIT.digest(), TOKEN, NOW), AgentRegistryException.Reason.NOT_FOUND);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, generation, launchId, INSTANCE,
                    TOKEN.digest(), NOW), AgentRegistryException.Reason.NOT_FOUND);
            assertFailure(() -> registry.renewReconnectToken(AGENT, generation, launchId, INSTANCE,
                    TOKEN.digest(), TOKEN.expiresAt(), NOW), AgentRegistryException.Reason.NOT_FOUND);
            registry.register(AGENT, "Build agent");
            assertFailure(() -> registry.consumeLaunchPermit(AGENT, generation, launchId, INSTANCE,
                    PERMIT.digest(), TOKEN, NOW), INVALID_STATE);
            assertFailure(() -> registry.verifyReconnectToken(AGENT, generation, launchId, INSTANCE,
                    TOKEN.digest(), NOW), INVALID_STATE);
            assertFailure(() -> registry.renewReconnectToken(AGENT, generation, launchId, INSTANCE,
                    TOKEN.digest(), TOKEN.expiresAt(), NOW), INVALID_STATE);
        }
        assertFailure(() -> registry.consumeLaunchPermit(AGENT, generation, launchId, INSTANCE,
                PERMIT.digest(), TOKEN, NOW), AgentRegistryException.Reason.CLOSED);
        assertFailure(() -> registry.verifyReconnectToken(AGENT, generation, launchId, INSTANCE,
                TOKEN.digest(), NOW), AgentRegistryException.Reason.CLOSED);
        assertFailure(() -> registry.renewReconnectToken(AGENT, generation, launchId, INSTANCE,
                TOKEN.digest(), TOKEN.expiresAt(), NOW), AgentRegistryException.Reason.CLOSED);
    }

    private static AgentRecord.Launch prepare(FileSystemAgentRegistry registry) throws AgentRegistryException {
        registry.register(AGENT, "Build agent");
        AgentRecord.Launch launch = registry.allocateLaunch(AGENT, registry.find(AGENT)
                    .flatMap(record -> record.registration().map(AgentRecord.Registration::instanceId))).launch().orElseThrow();
        return registry.installLaunchPermit(AGENT, launch.generation(), launch.launchId(), PERMIT, NOW)
                .launch().orElseThrow();
    }

    private static AgentRecord replace(FileSystemAgentRegistry registry) throws AgentRegistryException {
        AgentRecord.Launch next = registry.allocateLaunch(AGENT, Optional.of(INSTANCE)).launch().orElseThrow();
        registry.installLaunchPermit(AGENT, next.generation(), next.launchId(), PERMIT, NOW);
        return registry.consumeLaunchPermit(AGENT, next.generation(), next.launchId(),
                new AgentInstanceId(new UUID(0, 2)), PERMIT.digest(), TOKEN, NOW);
    }

    private static AgentRecord consume(FileSystemAgentRegistry registry, AgentRecord.Launch launch, Instant now)
            throws AgentRegistryException {
        return registry.consumeLaunchPermit(AGENT, launch.generation(), launch.launchId(), INSTANCE, PERMIT.digest(),
                TOKEN, now);
    }

    private static AgentRecord verify(FileSystemAgentRegistry registry, AgentRecord.Launch launch, Instant now)
            throws AgentRegistryException {
        return registry.verifyReconnectToken(AGENT, launch.generation(), launch.launchId(), INSTANCE, TOKEN.digest(), now);
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
