package pro.deta.orion.decision;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRequiredExceptionTest {
    @Test
    void handlerCanResolveWrappedFailureWithoutLosingItsOriginalCause() {
        AtomicInteger executed = new AtomicInteger();
        Decision decision = new Decision(UUID.randomUUID(),
                Optional.empty(), "Trust key", "",
                List.of(new DecisionAction("Add", false, actor -> { executed.incrementAndGet();
                return Result.of(null); })));
        IOException original = new IOException("rejected key");
        RuntimeException outer = new RuntimeException(new DecisionRequiredException(decision, original));
        assertThat(outer.getCause().getCause()).isSameAs(original);
        Decisionable required = (Decisionable) outer.getCause();
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            registry.register(required.decision()).valueOrFailure("register");
            DecisionAnswer answer = new DecisionAnswer(0, PrincipalAddress.parse("system/operator"));
            registry.decide(decision.request().id(), answer).valueOrFailure("answer");
            assertThat(decision.result().toCompletableFuture().join()).isEqualTo(Result.of(answer));
            assertThat(executed).hasValue(1);
            assertThat(registry.decide(decision.request().id(), answer).isFailure()).isTrue();
            assertThat(executed).hasValue(1);
        }
    }

    @Test
    void requiresAnOriginalFailureAndReadyResolution() {
        assertThatThrownBy(() -> new DecisionRequiredException(null, new IOException("rejected key")))
                .isInstanceOf(NullPointerException.class).hasMessage("decision");
        assertThatThrownBy(() -> new DecisionRequiredException(null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("cause");
    }
}
