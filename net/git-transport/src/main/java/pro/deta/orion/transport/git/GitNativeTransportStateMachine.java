package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionLifecycleStateMachine;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionEvent;
import pro.deta.orion.lifecycle.state.StateMachineEventSubscriber;
import pro.deta.orion.lifecycle.state.StateMachineSnapshot;
import pro.deta.orion.lifecycle.state.StateMachineSubscription;
import pro.deta.orion.lifecycle.state.Void;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;

@Singleton
public final class GitNativeTransportStateMachine implements OrionLifecycleStateMachine {
    public static final State RUNNING = state("RUNNING");

    private final GitNativeTransportService service;
    private final ActionBinding<Void> start;
    private final ActionBinding<Void> stop;
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public GitNativeTransportStateMachine(GitNativeTransportService service) {
        this.service = Objects.requireNonNull(service, "service");
        start = ActionId.START.bind(this::startGitTransport);
        stop = ActionId.STOP.bind(this::stopGitTransport);
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        if (!service.isEnabled()) {
            return;
        }
        registrar.task(this, ApplicationState.STARTING, OrionLifecycleTasks.GIT_TRANSPORT_START, () -> {
                    start();
                    return null;
                })
                .after(OrionLifecycleTasks.TRANSPORTS_START);
        registrar.task(this, ApplicationState.STOPPING, OrionLifecycleTasks.GIT_TRANSPORT_STOP, () -> {
            stop();
            return null;
        });
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(ERR)

                .from(NEW)
                .on(stop)
                .to(FIN)
                .failTo(ERR)

                .from(RUNNING)
                .on(stop)
                .to(FIN)
                .failTo(ERR)

                .from(ERR)
                .on(stop)
                .to(FIN)
                .failTo(ERR)
                .build();
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public GitNativeTransportService service() {
        return service;
    }

    public ActionBinding<Void> startAction() {
        return start;
    }

    public ActionBinding<Void> stopAction() {
        return stop;
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    public State currentState() {
        return stateMachine.currentState();
    }

    public StateMachineSnapshot snapshot() {
        return stateMachine.snapshot();
    }

    public String describe() {
        return stateMachine.describe();
    }

    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        return stateMachine.subscribe(subscriber);
    }

    public StateTransitionEvent start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateTransitionEvent stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private void startGitTransport(Void ignored) {
        service.onStart();
    }

    private void stopGitTransport(Void ignored) {
        service.onStop();
    }
}
