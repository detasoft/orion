package pro.deta.orion.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.PendingDecision;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshHostKeyDecisionTest {
    private static final PrincipalAddress ADMIN = PrincipalAddress.parse("system/admin");
    private static final PublicKey KEY = key();

    @Test
    void publishesTheServerKeyAndDeliversApprovalWithTheAnsweringPrincipal() {
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            PendingDecision pending = request(registry);
            assertThat(pending.request().scope()).isEmpty();
            assertThat(pending.request().title()).isEqualTo("Trust SSH host key for [server.test]:2222");
            assertThat(pending.request().description()).isEqualTo("Server: [server.test]:2222\nKey: "
                    + PublicKeyEntry.toString(KEY) + "\nFingerprint: " + KeyUtils.getFingerPrint(KEY));
            assertThat(pending.request().actions()).containsOnlyKeys("add", "reject");
            assertThat(registry.list(ADMIN)).containsExactly(pending.request());
            assertThat(pending.result().toCompletableFuture()).isNotDone();

            Decision answer = new Decision("add", ADMIN);
            registry.decide(pending.request().id(), answer).valueOrFailure("approve");

            assertThat(pending.result().toCompletableFuture().join()).isEqualTo(answer);
            assertThat(registry.list(ADMIN)).isEmpty();
            assertThat(pending.cancel()).isFalse();
            assertThat(registry.decide(pending.request().id(), new Decision("reject", ADMIN)).isFailure())
                    .isTrue();
        }
    }

    @Test
    void deliversRejectionWithoutTreatingItAsApproval() {
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            PendingDecision pending = request(registry);
            Decision answer = new Decision("reject", ADMIN);
            registry.decide(pending.request().id(), answer).valueOrFailure("reject");
            assertThat(pending.result().toCompletableFuture().join()).isEqualTo(answer);
            assertThat(registry.list(ADMIN)).isEmpty();
        }
    }

    @Test
    void scopesApprovalToTheSuppliedOrganization() {
        PrincipalAddress member = PrincipalAddress.parse("acme/operator");
        PrincipalAddress outsider = PrincipalAddress.parse("other/operator");
        Optional<ConfigurationScope> scope = Optional.of(ConfigurationScope.parse("acme/platform"));
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, selected) -> selected.equals(scope))) {
            PendingDecision pending = SshHostKeyDecision.request(registry, scope, "server.test", 22, KEY)
                    .valueOrFailure("request");
            assertThat(pending.request().scope()).isEqualTo(scope);
            assertThat(registry.list(outsider)).isEmpty();
            assertThat(registry.decide(pending.request().id(), new Decision("add", outsider)).isFailure())
                    .isTrue();
            assertThat(pending.result().toCompletableFuture()).isNotDone();
            assertThat(registry.list(member)).containsExactly(pending.request());
            Decision answer = new Decision("add", member);
            registry.decide(pending.request().id(), answer).valueOrFailure("approve");
            assertThat(pending.result().toCompletableFuture().join()).isEqualTo(answer);
        }
    }

    @Test
    void representsIpv6AndSerializesAnEd25519ServerKey() throws Exception {
        PublicKey jdkKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        PublicKey sshKey = SecurityUtils.getKeyFactory(SecurityUtils.EDDSA)
                .generatePublic(new X509EncodedKeySpec(jdkKey.getEncoded()));
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            PendingDecision pending = SshHostKeyDecision.request(registry, Optional.empty(), "::1", 22, sshKey)
                    .valueOrFailure("request");
            assertThat(pending.request().description()).contains("Server: [::1]:22", "Key: ssh-ed25519 ",
                    PublicKeyEntry.toString(sshKey), KeyUtils.getFingerPrint(sshKey));
        }
    }

    @Test
    void cancellationAndRegistryShutdownCompleteOutstandingWaits() {
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            PendingDecision cancelled = request(registry);
            assertThat(cancelled.cancel()).isTrue();
            assertThatThrownBy(() -> cancelled.result().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            PendingDecision stopped = request(registry);
            registry.close();
            assertThatThrownBy(() -> stopped.result().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            assertThat(registry.list(ADMIN)).isEmpty();
            assertThat(SshHostKeyDecision.request(registry, Optional.empty(), "server.test", 22, KEY).isFailure())
                    .isTrue();
        }
    }

    @Test
    void propagatesRegistryCapacityFailure() {
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            PendingDecision first = request(registry);
            assertThat(SshHostKeyDecision.request(registry, Optional.empty(), "other.test", 22, KEY))
                    .isInstanceOfSatisfying(Result.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.CREATION_FAILED));
            assertThat(registry.list(ADMIN)).containsExactly(first.request());
        }
    }

    @Test
    void unsupportedKeyDoesNotCreateARequest() {
        PublicKey unsupported = new PublicKey() {
            @Override public String getAlgorithm() { return "unsupported-test-key"; }
            @Override public String getFormat() { return "RAW"; }
            @Override public byte[] getEncoded() { return new byte[]{1}; }
        };
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            assertThat(SshHostKeyDecision.request(registry, Optional.empty(), "server.test", 22, unsupported))
                    .isInstanceOfSatisfying(Result.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_SUPPORTED));
            assertThat(registry.list(ADMIN)).isEmpty();
        }
    }

    @Test
    void invalidEndpointDoesNotCreateARequest() {
        try (DecisionRegistry registry = new DecisionRegistry(1, (actor, scope) -> true)) {
            assertThatThrownBy(() -> SshHostKeyDecision.request(registry, Optional.empty(), " ", 22, KEY))
                    .isInstanceOf(IllegalArgumentException.class);
            for (int port : new int[]{0, -1, 65536}) {
                assertThatThrownBy(() -> SshHostKeyDecision.request(registry, Optional.empty(), "host", port, KEY))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(registry.list(ADMIN)).isEmpty();
        }
    }

    private static PendingDecision request(DecisionRegistry registry) {
        return SshHostKeyDecision.request(registry, Optional.empty(), "server.test", 2222, KEY)
                .valueOrFailure("request");
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
