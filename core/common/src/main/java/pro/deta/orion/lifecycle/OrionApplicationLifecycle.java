package pro.deta.orion.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineEventType;
import pro.deta.orion.lifecycle.state.StateMachineSubscription;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.OrionUtils;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

@Slf4j
@Singleton
public class OrionApplicationLifecycle {
    public final static ApplicationBootstrap BOOTSTRAP = new ApplicationBootstrap();
    private static final long SHUTDOWN_REQUEST_DELAY_MILLIS = 100;

    private final AggregateStateMachine runtimeStateMachine;
    private final OrionProvider orionProvider;

    @Inject
    public OrionApplicationLifecycle(
            @Named("runtime") AggregateStateMachine runtimeStateMachine,
            OrionProvider orionProvider) {
        this.runtimeStateMachine = runtimeStateMachine;
        this.orionProvider = orionProvider;
        registerLifecycleEventHandlers();
    }

    private StateMachineDefinition.State runRuntimeTransition(ActionId actionId, Runnable transition) {
        log.warn("[{}] lifecycle transition initiated...", actionId);
        try {
            transition.run();
        } catch (StateTransitionFailedException e) {
            log.error("[{}] runtime state machine transition failed.", actionId, e);
        }
        StateMachineDefinition.State state = runtimeStateMachine.currentState();
        log.warn("[{}] lifecycle transition completed with state {}.", actionId, state);
        return state;
    }

    public String describeLifecycle() {
        return runtimeStateMachine.describeStatus();
    }

    public StateMachineDefinition.State runApplication() {
        if (log.isDebugEnabled()) {
            log.debug("Lifecycle before initialization:\n{}", describeLifecycle());
        }
        return runRuntimeTransition(ActionId.START, runtimeStateMachine::start);
    }

    public StateMachineDefinition.State shutdownApplication() {
        log.info("System shutdown process initiated.");
        return runRuntimeTransition(ActionId.STOP, runtimeStateMachine::stop);
    }

    private void doShutdown() {
        shutdownApplication();
    }

    private void registerLifecycleEventHandlers() {
        orionProvider.getEventManager().registerTypeHandler(
                ApplicationShutdownRequestedEvent.class,
                this::handleShutdownRequested);
    }

    private void handleShutdownRequested(ApplicationShutdownRequestedEvent event) {
        orionProvider.getOrionExecutor().schedule(this::doShutdown, SHUTDOWN_REQUEST_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void beginShutdown() {
        orionProvider.getOrionExecutor().submit(this::doShutdown);
    }

    public void waitForStarting() {
        waitForState(RUNNING);
        OrionUtils.waitForCondition(() -> orionProvider.getEventManager().getUnprocessedLength() == 0);
    }

    public void waitForShutdown() {
        waitForState(FIN);
    }

    private void waitForState(StateMachineDefinition.State state) {
        Objects.requireNonNull(state, "state");
        if (runtimeStateMachine.currentState().equals(state)) {
            return;
        }

        CountDownLatch stateReached = new CountDownLatch(1);
        try (StateMachineSubscription ignored = runtimeStateMachine.subscribe(event -> {
            if (event.type() == StateMachineEventType.AFTER_STATE_ENTERED
                    && event.currentState().equals(state)) {
                stateReached.countDown();
            }
        })) {
            if (runtimeStateMachine.currentState().equals(state)) {
                return;
            }
            awaitState(stateReached, state);
        }
    }

    private static void awaitState(CountDownLatch stateReached, StateMachineDefinition.State state) {
        try {
            stateReached.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for runtime state " + state, e);
        }
    }
}
