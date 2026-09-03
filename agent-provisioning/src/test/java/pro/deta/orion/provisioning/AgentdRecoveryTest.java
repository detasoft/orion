package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentdRecoveryTest {
    @Test
    void reconnectionBeforeSustainedOfflineDoesNotCreateAttempt() throws Exception {
        RecordingSource source = new RecordingSource();
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(false), source, (attempt, previous) -> null,
                options(3), duration -> { });

        assertThat(recovery.recover(null)).isNull();
        assertThat(source.requested).isZero();
        assertThat(recovery.state()).isEqualTo(AgentdRecovery.State.WAITING_OFFLINE);
    }

    @Test
    void startupTimeoutUsesFreshAttemptTerminatesPriorIdentityAndCapsBackoff() throws Exception {
        AgentdLaunchAttempt first = attempt(1);
        AgentdLaunchAttempt second = attempt(2);
        AgentdLaunchAttempt third = attempt(3);
        RecordingSource source = new RecordingSource(first, second, third);
        FakeAvailability availability = new FakeAvailability(true, false, false, true);
        List<Duration> sleeps = new ArrayList<>();
        List<AgentdProcessIdentity> previous = new ArrayList<>();
        AgentdReplacement replacement = (attempt, old) -> {
            previous.add(old);
            AgentdProcessIdentity identity = identity(attempt.request());
            return new AgentdReplacementResult(
                    AgentdReplacementResult.State.LAUNCHED, identity,
                    new ProvisioningResult(RemotePlatform.LINUX_X86_64,
                            attempt.request().agentVersion(), identity.releaseDirectory()));
        };
        AgentdRecovery recovery = new AgentdRecovery(
                availability, source, replacement, options(3), sleeps::add);

        AgentdReplacementResult result = recovery.recover(null);

        assertThat(result.identity().launchId()).isEqualTo(third.request().launchId());
        assertThat(previous).containsExactly(null, identity(first.request()), identity(second.request()));
        assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(15));
        assertThat(availability.onlineLaunches).containsExactly(
                first.request().launchId(), second.request().launchId(), third.request().launchId());
        assertThat(recovery.state()).isEqualTo(AgentdRecovery.State.ONLINE);
    }

    @Test
    void exhaustionIsTypedAndEveryPermitIsClosed() throws Exception {
        AgentdLaunchAttempt first = attempt(1);
        AgentdLaunchAttempt second = attempt(2);
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true, false, false), new RecordingSource(first, second),
                (attempt, old) -> new AgentdReplacementResult(
                        AgentdReplacementResult.State.LAUNCHED, identity(attempt.request()),
                        new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1", "/opt/orion/releases/1")),
                options(2), duration -> { });

        assertThatThrownBy(() -> recovery.recover(null))
                .isInstanceOf(ProvisioningException.class)
                .satisfies(error -> {
                    ProvisioningException exhausted = (ProvisioningException) error;
                    assertThat(exhausted.failure()).isEqualTo(ProvisioningFailure.RETRIES_EXHAUSTED);
                    assertThat(exhausted).hasCauseInstanceOf(ProvisioningException.class)
                            .hasMessageContaining("STARTUP_TIMEOUT")
                            .hasMessageContaining(second.request().launchId().value().toString());
                    assertThat(((ProvisioningException) exhausted.getCause()).failure())
                            .isEqualTo(ProvisioningFailure.STARTUP_TIMEOUT);
                });
        assertThatThrownBy(first.permit()::copyBytes).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(second.permit()::copyBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonIncreasingGenerationAgainstLiveStateAndClosesPermit() {
        AgentdLaunchAttempt attempt = attempt(10);
        AgentdProcessIdentity previous = identity(request(10));
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true), new RecordingSource(attempt),
                (candidate, old) -> { throw new AssertionError("replacement must not run"); },
                options(1), duration -> { });

        assertThatThrownBy(() -> recovery.recover(previous))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThatThrownBy(attempt.permit()::copyBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsGenerationThatDecreasesAcrossRetries() {
        AgentdLaunchAttempt first = attempt(12);
        AgentdLaunchAttempt lower = attempt(11);
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true, false), new RecordingSource(first, lower),
                (candidate, old) -> new AgentdReplacementResult(
                        AgentdReplacementResult.State.LAUNCHED, identity(candidate.request()),
                        new ProvisioningResult(RemotePlatform.LINUX_X86_64,
                                candidate.request().agentVersion(), identity(candidate.request()).releaseDirectory())),
                options(2), duration -> { });

        assertThatThrownBy(() -> recovery.recover(null))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThatThrownBy(lower.permit()::copyBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retriesFailureBeforeLaunchWithFreshPermitAndCappedBackoff() throws Exception {
        AgentdLaunchAttempt first = attempt(1);
        AgentdLaunchAttempt second = attempt(2);
        List<Duration> sleeps = new ArrayList<>();
        int[] calls = {0};
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true, true), new RecordingSource(first, second),
                (candidate, old) -> {
                    if (calls[0]++ == 0) {
                        throw new ProvisioningException(ProvisioningFailure.LAUNCH, "not launched");
                    }
                    return result(candidate);
                }, options(2), sleeps::add);

        AgentdReplacementResult recovered = recovery.recover(null);

        assertThat(recovered.identity()).isEqualTo(identity(second.request()));
        assertThat(sleeps).containsExactly(Duration.ofMillis(10));
        assertThatThrownBy(first.permit()::copyBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicationTimeoutBeforeCommitRecoversSameAttemptWithoutFreshGeneration() throws Exception {
        assertAmbiguousPublicationRecovered("before commit");
    }

    @Test
    void publicationTimeoutAfterCommitRecoversSameAttemptWithoutFreshGeneration() throws Exception {
        assertAmbiguousPublicationRecovered("after commit");
    }

    private static void assertAmbiguousPublicationRecovered(String phase) throws Exception {
        AgentdLaunchAttempt first = attempt(1);
        AgentdProcessIdentity partial = identity(first.request());
        List<AgentdProcessIdentity> previous = new ArrayList<>();
        RecordingSource source = new RecordingSource(first);
        AgentdReplacement replacement = new AgentdReplacement() {
            @Override
            public AgentdReplacementResult reconcile(
                    AgentdLaunchAttempt candidate,
                    AgentdProcessIdentity old) throws ProvisioningException {
                previous.add(old);
                throw new AgentdPartialLaunchException(
                        ProvisioningFailure.TIMEOUT, "publication timeout " + phase, partial, null);
            }

            @Override
            public AgentdReplacementResult recoverPartial(
                    AgentdLaunchAttempt candidate,
                    AgentdProcessIdentity observed) {
                previous.add(observed);
                return result(candidate);
            }
        };
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true, true), source, replacement, options(2), duration -> { });

        AgentdReplacementResult recovered = recovery.recover(null);

        assertThat(recovered.identity()).isEqualTo(partial);
        assertThat(previous).containsExactly(null, partial);
        assertThat(source.requested).isOne();
    }

    @Test
    void partialLaunchExceptionRejectsMissingSignalAuthority() {
        assertThatThrownBy(() -> new AgentdPartialLaunchException(
                ProvisioningFailure.TIMEOUT, "missing", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("identity");
    }

    @Test
    void partialRecoveryRejectsChangedPidWithSameLogicalLaunchIdentity() {
        AgentdProcessIdentity expected = identity(request(1));
        AgentdProcessIdentity changed = new AgentdProcessIdentity(
                expected.pid() + 1, expected.startEpochMillis(), expected.nativeStartToken(),
                expected.releaseDirectory(), expected.executable(), expected.launchId(), expected.generation());

        assertPartialMismatchRejected(expected, changed);
    }

    @Test
    void partialRecoveryRejectsChangedExecutableWithSameLogicalLaunchIdentity() {
        AgentdProcessIdentity expected = identity(request(1));
        AgentdProcessIdentity changed = new AgentdProcessIdentity(
                expected.pid(), expected.startEpochMillis(), expected.nativeStartToken(),
                expected.releaseDirectory(), expected.executable() + "-other",
                expected.launchId(), expected.generation());

        assertPartialMismatchRejected(expected, changed);
    }

    private static void assertPartialMismatchRejected(
            AgentdProcessIdentity expected, AgentdProcessIdentity changed) {
        AgentdLaunchAttempt attempt = attempt(1);
        RecordingSource source = new RecordingSource(attempt);
        AgentdReplacement replacement = new AgentdReplacement() {
            @Override
            public AgentdReplacementResult reconcile(
                    AgentdLaunchAttempt candidate,
                    AgentdProcessIdentity old) throws ProvisioningException {
                throw new AgentdPartialLaunchException(
                        ProvisioningFailure.TIMEOUT, "publication response lost", expected, null);
            }

            @Override
            public AgentdReplacementResult recoverPartial(
                    AgentdLaunchAttempt candidate,
                    AgentdProcessIdentity partial) {
                return new AgentdReplacementResult(
                        AgentdReplacementResult.State.ADOPTED, changed,
                        new ProvisioningResult(
                                RemotePlatform.LINUX_X86_64, "1", changed.releaseDirectory()));
            }
        };
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true), source, replacement, options(2), duration -> { });

        assertThatThrownBy(() -> recovery.recover(null))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThat(source.requested).isOne();
        assertThatThrownBy(attempt.permit()::copyBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unsafeFailureDoesNotRetry() {
        AgentdLaunchAttempt attempt = attempt(1);
        RecordingSource source = new RecordingSource(attempt, attempt(2));
        AgentdRecovery recovery = new AgentdRecovery(
                new FakeAvailability(true), source,
                (candidate, old) -> {
                    throw new ProvisioningException(ProvisioningFailure.UNSAFE_IDENTITY, "unsafe");
                }, options(2), duration -> { });

        assertThatThrownBy(() -> recovery.recover(null))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNSAFE_IDENTITY);
        assertThat(source.requested).isOne();
    }

    @Test
    void slowReconciliationDoesNotConsumeExactLaunchOnlineWindow() throws Exception {
        AgentdLaunchAttempt attempt = attempt(1);
        FakeAvailability availability = new FakeAvailability(true, true);
        AgentdRecovery recovery = new AgentdRecovery(
                availability, new RecordingSource(attempt),
                (candidate, old) -> {
                    Thread.sleep(25);
                    return result(candidate);
                }, options(1), duration -> { });

        recovery.recover(null);

        assertThat(availability.onlineTimeouts)
                .containsExactly(options(1).startupTimeout());
    }

    private static AgentdReplacementResult result(AgentdLaunchAttempt attempt) {
        AgentdProcessIdentity identity = identity(attempt.request());
        return new AgentdReplacementResult(
                AgentdReplacementResult.State.LAUNCHED, identity,
                new ProvisioningResult(RemotePlatform.LINUX_X86_64,
                        attempt.request().agentVersion(), identity.releaseDirectory()));
    }

    private static AgentdRecoveryOptions options(int attempts) {
        return new AgentdRecoveryOptions(
                Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(10), Duration.ofMillis(15), attempts);
    }

    private static AgentdLaunchAttempt attempt(long generation) {
        AgentdLaunchRequest request = request(generation);
        return new AgentdLaunchAttempt(
                request, new ProvisioningLaunchPermit(("permit-" + generation)
                .getBytes(StandardCharsets.US_ASCII)));
    }

    private static AgentdLaunchRequest request(long generation) {
        return new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"), "/var/lib/orion/agent",
                new AgentId("agent-1"), new AgentGeneration(generation),
                new AgentLaunchId(new UUID(0, generation)), 1024, Long.toString(generation));
    }

    private static AgentdProcessIdentity identity(AgentdLaunchRequest request) {
        String release = "/opt/orion/releases/" + request.agentVersion();
        return new AgentdProcessIdentity(
                100 + request.generation().value(), 1_000, Long.toString(request.generation().value()),
                release, release + "/agentd", request.launchId(), request.generation());
    }

    private static final class FakeAvailability implements AgentdAvailability {
        private final ArrayDeque<Boolean> answers = new ArrayDeque<>();
        private final List<AgentLaunchId> onlineLaunches = new ArrayList<>();
        private final List<Duration> onlineTimeouts = new ArrayList<>();

        private FakeAvailability(boolean... answers) {
            for (boolean answer : answers) {
                this.answers.add(answer);
            }
        }

        @Override
        public boolean awaitSustainedOffline(Duration timeout) {
            return answers.removeFirst();
        }

        @Override
        public boolean awaitOnline(AgentLaunchId launchId, Duration timeout) {
            onlineLaunches.add(launchId);
            onlineTimeouts.add(timeout);
            return answers.removeFirst();
        }
    }

    private static final class RecordingSource implements AgentdLaunchAttemptSource {
        private final ArrayDeque<AgentdLaunchAttempt> attempts = new ArrayDeque<>();
        private int requested;

        private RecordingSource(AgentdLaunchAttempt... attempts) {
            this.attempts.addAll(List.of(attempts));
        }

        @Override
        public AgentdLaunchAttempt nextAttempt() {
            requested++;
            return attempts.removeFirst();
        }
    }
}
