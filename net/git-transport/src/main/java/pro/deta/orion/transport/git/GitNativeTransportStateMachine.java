package pro.deta.orion.transport.git;

import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ServiceLifecycleState;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineEvent;
import pro.deta.orion.lifecycle.state.StateMachineSnapshot;
import pro.deta.orion.lifecycle.state.Void;

import java.util.Objects;

import static pro.deta.orion.lifecycle.state.ServiceLifecycleState.ERR;
import static pro.deta.orion.lifecycle.state.ServiceLifecycleState.FIN;
import static pro.deta.orion.lifecycle.state.ServiceLifecycleState.NEW;
import static pro.deta.orion.lifecycle.state.ServiceLifecycleState.RUNNING;

public final class GitNativeTransportStateMachine {
    private final GitNativeTransportService service;
    private final ActionBinding<Void> start;
    private final ActionBinding<Void> stop;
    private final StateMachine<ServiceLifecycleState> stateMachine;

    public GitNativeTransportStateMachine(GitNativeTransportService service) {
        this.service = Objects.requireNonNull(service, "service");
        start = ActionBinding.of("git-native-transport.start", Void.class, this::startGitTransport);
        stop = ActionBinding.of("git-native-transport.stop", Void.class, this::stopGitTransport);
        stateMachine = definition().newStateMachine();
    }

    public StateMachineDefinition<ServiceLifecycleState> definition() {
        return StateMachineDefinition.startingAt(NEW)
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

                .terminal(FIN)
                .build();
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

    public StateMachine<ServiceLifecycleState> stateMachine() {
        return stateMachine;
    }

    public ServiceLifecycleState currentState() {
        return stateMachine.currentState();
    }

    public StateMachineSnapshot<ServiceLifecycleState> snapshot() {
        return stateMachine.snapshot();
    }

    public StateMachineEvent<ServiceLifecycleState> start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateMachineEvent<ServiceLifecycleState> stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private void startGitTransport(Void ignored) {
        service.onStart();
    }

    private void stopGitTransport(Void ignored) {
        service.onStop();
    }
}
