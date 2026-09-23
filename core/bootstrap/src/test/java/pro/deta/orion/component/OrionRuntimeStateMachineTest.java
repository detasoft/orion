package pro.deta.orion.component;

import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.OrionAccessControlStateMachine;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.OrionEventManagerStateMachine;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionExecutorStateMachine;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.transport.TransportLifecycleStateMachine;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;

class OrionRuntimeStateMachineTest {
    @Test
    void stopBeforeStartCancelsDecisionsWithoutCreatingServices() {
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry decisions = OrionRuntimeModule.decisionRegistry(executor)) {
            OrionRuntimeStateMachine runtime = runtime(decisions,
                    new OrionExecutorStateMachine(OrionRuntimeStateMachineTest::unstartedService),
                    new OrionEventManagerStateMachine(OrionRuntimeStateMachineTest::unstartedService));
            Decision pending = register(decisions).valueOrFailure("register decision");

            runtime.stop();

            assertThat(runtime.currentState()).isEqualTo(FIN);
            assertThat(pending.result().toCompletableFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                .toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CancellationException.class);
            assertThat(register(decisions)).isInstanceOf(Result.Failure.class);
        }
    }

    @Test
    void stopAfterFailedStartupDispatchesCancellationBeforeExecutorShutdown() throws Exception {
        try (OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
                DecisionRegistry decisions = OrionRuntimeModule.decisionRegistry(executor)) {
            OrionEventManager events = new OrionEventManager() {
                @Override
                public void onStart() {
                    throw new IllegalStateException("startup failure");
                }

                @Override
                public void onStop() {
                }
            };
            OrionRuntimeStateMachine runtime = runtime(decisions,
                    new OrionExecutorStateMachine(() -> executor),
                    new OrionEventManagerStateMachine(() -> events));
            Decision pending = register(decisions).valueOrFailure("register decision");
            CompletableFuture<Throwable> continued = pending.result()
                .thenApply(result -> result.valueOrFailure("decision execution"))
                    .handleAsync((decision, failure) -> failure, executor).toCompletableFuture();
            assertThatThrownBy(runtime::start).isInstanceOf(StateTransitionFailedException.class);
            assertThat(runtime.currentState()).isEqualTo(ERR);
            assertThat(executor.isShutdown()).isFalse();

            runtime.stop();

            assertThat(continued.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CancellationException.class);
            assertThat(executor.isShutdown()).isTrue();
            assertThat(runtime.currentState()).isEqualTo(FIN);
            assertThat(register(decisions)).isInstanceOf(Result.Failure.class);
        }
    }

    private static OrionRuntimeStateMachine runtime(DecisionRegistry decisions,
            OrionExecutorStateMachine executor, OrionEventManagerStateMachine events) {
        return new OrionRuntimeStateMachine(executor, events,
                new OrionAccessControlStateMachine(OrionRuntimeStateMachineTest::unstartedService),
                new AgentSessionServerStateMachine(OrionRuntimeStateMachineTest::unstartedService),
                new TransportLifecycleStateMachine(
                        new GitNativeTransportStateMachine(OrionRuntimeStateMachineTest::unstartedService),
                        new GitSshTransportStateMachine(OrionRuntimeStateMachineTest::unstartedService),
                        new JettyHTTPServerStateMachine(OrionRuntimeStateMachineTest::unstartedService)),
                () -> { throw new AssertionError("Bootstrap proxies must not start"); }, decisions);
    }
    private static Result<Decision> register(DecisionRegistry decisions) {
        return decisions.register(new Decision(UUID.randomUUID(),
                Optional.empty(), "Confirm operation", "",
                List.of(new DecisionAction("Accept", false, actor -> Result.of(null)))));
    }

    private static <T> T unstartedService() {
        throw new AssertionError("Unstarted service must not be created");
    }
}
