package pro.deta.orion.transport.git;

import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateMachineEvent;
import pro.deta.orion.lifecycle.state.StateMachineEventSubscriber;
import pro.deta.orion.lifecycle.state.StateMachineSnapshot;
import pro.deta.orion.lifecycle.state.StateMachineSubscription;
import pro.deta.orion.lifecycle.state.Void;

import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;

public final class GitNativeTransportStateMachine {
    public static final State RUNNING = state("RUNNING");

    private final GitNativeTransportService service;
    private final ActionBinding<Void> start;
    private final ActionBinding<Void> stop;
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    public GitNativeTransportStateMachine(GitNativeTransportService service) {
        this.service = Objects.requireNonNull(service, "service");
        start = ActionBinding.of("git-native-transport.start", this::startGitTransport);
        stop = ActionBinding.of("git-native-transport.stop", this::stopGitTransport);
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
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

    public StateMachineEvent start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateMachineEvent stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private void startGitTransport(Void ignored) {
        service.onStart();
    }

    private void stopGitTransport(Void ignored) {
        service.onStop();
    }
}
