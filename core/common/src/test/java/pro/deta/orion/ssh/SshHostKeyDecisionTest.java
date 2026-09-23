package pro.deta.orion.ssh;

import pro.deta.orion.decision.DecisionAction;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshHostKeyDecisionTest {
    private static final PrincipalAddress ADMIN = PrincipalAddress.parse("system/admin");
    private static final PublicKey KEY = key();

    @Test
    void approvalRunsTheCapturedOperationAndCompletesOnlyAfterItFinishes() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AtomicReference<PrincipalAddress> executedBy = new AtomicReference<>();
        try (DecisionRegistry registry = new DecisionRegistry(1, work::add, (actor, scope) -> true)) {
            SshHostKeyDecision decision = SshHostKeyDecision.create("ssh-connection", Optional.empty(), "server.test", 2222, KEY,
                    new DecisionAction("Add and trust", actor -> { executedBy.set(actor); return Result.of(null); }))
                    .valueOrFailure("create");
            registry.register(decision).valueOrFailure("register");
            assertThat(decision.request().scope()).isEmpty();
            assertThat(decision.request().title()).isEqualTo("Trust SSH host key for [server.test]:2222");
            assertThat(decision.request().description()).isEqualTo("Server: [server.test]:2222\nKey: "
                    + PublicKeyEntry.toString(KEY) + "\nFingerprint: " + KeyUtils.getFingerPrint(KEY));
            assertThat(decision.request().actions()).containsOnlyKeys("0", "1");
            assertThat(registry.list(ADMIN)).containsExactly(decision.request());
            DecisionAnswer answer = new DecisionAnswer(0, ADMIN);
            registry.decide(decision.request().id(), answer).valueOrFailure("approve");
            assertThat(executedBy).hasNullValue();
            assertThat(decision.result().toCompletableFuture()).isNotDone();
            assertThat(registry.list(ADMIN)).isEmpty();
            assertThat(decision.cancel()).isFalse();
            assertThat(work).hasSize(1);
            work.remove().run();
            assertThat(executedBy).hasValue(ADMIN);
            assertThat(decision.result().toCompletableFuture().join()).isEqualTo(Result.of(answer));
            assertThat(registry.decide(decision.request().id(), answer).isFailure()).isTrue();
            assertThat(work).isEmpty();
        }
    }

    @Test
    void rejectionCompletesWithoutExecutingTheTrustOperation() {
        AtomicInteger trusted = new AtomicInteger();
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            SshHostKeyDecision decision = SshHostKeyDecision.create("ssh-connection", Optional.empty(), "host", 22, KEY,
                    new DecisionAction("Add and trust", actor -> { trusted.incrementAndGet(); return Result.of(null); }))
                    .valueOrFailure("create");
            registry.register(decision).valueOrFailure("register");
            registry.decide(decision.request().id(), new DecisionAnswer(1, ADMIN)).valueOrFailure("answer");
            assertThat(decision.result().toCompletableFuture().join())
                    .isInstanceOfSatisfying(Result.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.FALSE));
            assertThat(trusted).hasValue(0);
            assertThat(registry.list(ADMIN)).isEmpty();
        }
    }
    @Test
    void failedTrustOperationDoesNotReportSuccess() {
        Result.Failure<Void> failed = new Result.Failure<>(Result.FailureCode.GENERAL, "save failed");
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            Decision decision = SshHostKeyDecision.create("ssh-connection", Optional.empty(), "host", 22, KEY,
                    new DecisionAction("Add and trust", actor -> failed)).valueOrFailure("create");
            registry.register(decision).valueOrFailure("register");
            registry.decide(decision.request().id(), new DecisionAnswer(0, ADMIN)).valueOrFailure("answer");
            assertThat(decision.result().toCompletableFuture().join()).isEqualTo(failed);
        }
    }

    @Test
    void scopesApprovalToTheSuppliedOrganization() {
        PrincipalAddress member = PrincipalAddress.parse("acme/operator");
        PrincipalAddress outsider = PrincipalAddress.parse("other/operator");
        Optional<ConfigurationScope> scope = Optional.of(ConfigurationScope.parse("acme/platform"));
        AtomicReference<PrincipalAddress> trustedBy = new AtomicReference<>();
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, selected) -> selected.equals(scope))) {
            Decision decision = SshHostKeyDecision.create("ssh-connection", scope, "server.test", 22, KEY,
                    new DecisionAction("Add and trust", actor -> { trustedBy.set(actor); return Result.of(null); }))
                    .valueOrFailure("create");
            registry.register(decision).valueOrFailure("register");
            assertThat(decision.request().scope()).isEqualTo(scope);
            assertThat(registry.list(outsider)).isEmpty();
            assertThat(registry.decide(decision.request().id(), new DecisionAnswer(0, outsider)).isFailure())
                    .isTrue();
            assertThat(trustedBy).hasNullValue();
            assertThat(decision.result().toCompletableFuture()).isNotDone();
            registry.decide(decision.request().id(), new DecisionAnswer(0, member)).valueOrFailure("approve");
            assertThat(trustedBy).hasValue(member);
        }
    }

    @Test
    void representsIpv6AndSerializesAnEd25519ServerKey() throws Exception {
        PublicKey jdkKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        PublicKey sshKey = SecurityUtils.getKeyFactory(SecurityUtils.EDDSA)
                .generatePublic(new X509EncodedKeySpec(jdkKey.getEncoded()));
        Decision decision = SshHostKeyDecision.create("ssh-connection", Optional.empty(), "::1", 22, sshKey,
                new DecisionAction("Add and trust", actor -> Result.of(null))).valueOrFailure("create");
        assertThat(decision.request().description()).contains("Server: [::1]:22", "Key: ssh-ed25519 ",
                PublicKeyEntry.toString(sshKey), KeyUtils.getFingerPrint(sshKey));
    }
    @Test
    void cancellationAndRegistryShutdownPreventExecution() {
        AtomicInteger executed = new AtomicInteger();
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            Decision cancelled = SshHostKeyDecision.create("ssh-connection", Optional.empty(), "host", 22, KEY,
                    new DecisionAction("Add and trust", actor -> { executed.incrementAndGet(); return Result.of(null); }))
                    .valueOrFailure("create");
            registry.register(cancelled).valueOrFailure("register");
            assertThat(cancelled.cancel()).isTrue();
            assertThatThrownBy(() -> cancelled.result().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            Decision stopped = SshHostKeyDecision.create("ssh-connection", Optional.empty(), "host", 22, KEY,
                    new DecisionAction("Add and trust", actor -> { executed.incrementAndGet(); return Result.of(null); }))
                    .valueOrFailure("create");
            registry.register(stopped).valueOrFailure("register");
            registry.close();
            assertThatThrownBy(() -> stopped.result().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            assertThat(executed).hasValue(0);
        }
    }

    @Test
    void unsupportedKeyDoesNotProduceADecision() {
        PublicKey unsupported = new PublicKey() {
            @Override public String getAlgorithm() { return "unsupported-test-key"; }
            @Override public String getFormat() { return "RAW"; }
            @Override public byte[] getEncoded() { return new byte[]{1}; }
        };
        assertThat(SshHostKeyDecision.create("ssh-connection", Optional.empty(), "host", 22, unsupported,
                new DecisionAction("Add and trust", actor -> Result.of(null))))
                .isInstanceOfSatisfying(Result.Failure.class,
                        failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_SUPPORTED));
    }

    @Test
    void invalidEndpointDoesNotProduceADecision() {
        assertThatThrownBy(() -> SshHostKeyDecision.create("ssh-connection", Optional.empty(), " ", 22, KEY,
                new DecisionAction("Add and trust", actor -> Result.of(null)))).isInstanceOf(IllegalArgumentException.class);
        for (int port : new int[]{0, -1, 65536}) {
            assertThatThrownBy(() -> SshHostKeyDecision.create("ssh-connection", Optional.empty(), "host", port, KEY,
                    new DecisionAction("Add and trust", actor -> Result.of(null)))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static PublicKey key() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair().getPublic();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
