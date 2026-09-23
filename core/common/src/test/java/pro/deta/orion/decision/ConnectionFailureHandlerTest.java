package pro.deta.orion.decision;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionFailureHandlerTest {
    private static final PrincipalAddress ADMIN = PrincipalAddress.parse("system/admin");

    @Test
    void findsSuppressedDecisionPreservesCauseAndReusesItWhileExecuting() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        try (DecisionRegistry registry = new DecisionRegistry(1, work::add, (actor, scope) -> true)) {
            ConnectionFailureHandler handler = new ConnectionFailureHandler(registry);
            Decision decision = decision("connection");
            Exception original = new IOException("wrapped");
            original.addSuppressed(new DecisionRequiredException(decision, new IOException("key rejected")));
            DecisionRequiredException registered = (DecisionRequiredException) handler.handle(original);
            assertThat(registered.getCause()).isSameAs(original);
            assertThat(registered.decision()).isSameAs(decision);
            assertThat(registry.list(ADMIN)).containsExactly(decision.request());
            registry.decide(decision.request().id(), new DecisionAnswer(0, ADMIN)).valueOrFailure("answer");
            assertThat(((DecisionRequiredException) handler.handle(registered)).decision()).isSameAs(decision);
            assertThat(work).hasSize(1);
            work.remove().run();
        }
    }

    @Test
    void leavesUnresolvableCyclicFailureUnchangedAndReportsRegistrationFailure() {
        try (DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            ConnectionFailureHandler handler = new ConnectionFailureHandler(registry);
            Exception first = new IOException("first");
            Exception second = new IOException("second", first);
            first.initCause(second);
            second.addSuppressed(first);
            assertThat(handler.handle(first)).isSameAs(first);
            handler.handle(new DecisionRequiredException(decision("one"), first));
            Exception rejected = new DecisionRequiredException(decision("two"), first);
            assertThatThrownBy(() -> handler.handle(rejected))
                    .isInstanceOf(RejectedExecutionException.class).hasCause(rejected);
        }
    }

    private static Decision decision(String resource) {
        return new Decision(resource, Optional.empty(), "Confirm", "",
                List.of(new DecisionAction("Accept", false, actor -> Result.of(null))));
    }
}
