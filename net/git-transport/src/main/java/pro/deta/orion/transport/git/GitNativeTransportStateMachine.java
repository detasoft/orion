package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionResult;
import pro.deta.orion.lifecycle.state.StateMachineEventSubscriber;
import pro.deta.orion.lifecycle.state.StateMachineSnapshot;
import pro.deta.orion.lifecycle.state.StateMachineSubscription;
import pro.deta.orion.lifecycle.state.Void;

import javax.inject.Provider;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.*;

@Singleton
public final class GitNativeTransportStateMachine {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final Provider<GitNativeTransportService> serviceProvider;
    private volatile GitNativeTransportService service;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startGitTransport);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopGitTransport);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public GitNativeTransportStateMachine(
            Provider<GitNativeTransportService> serviceProvider) {
        this.serviceProvider = Objects.requireNonNull(serviceProvider, "serviceProvider");
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .name("git-native")
                .from(NEW, DISABLED)
                .on(start)
                .to(DISABLED, RUNNING, ERR)
                .post(this::resolveStartState)

                .from(NEW, DISABLED)
                .on(stop)
                .to(FIN, ERR)

                .from(RUNNING)
                .on(stop)
                .to(FIN, ERR)

                .from(ERR)
                .on(stop)
                .to(FIN, ERR)
                .build();
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public GitNativeTransportService service() {
        return resolveService();
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

    public StateTransitionResult start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateTransitionResult stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private OrionStageCallResult startGitTransport(Void ignored) {
        return resolveService().onStart();
    }

    private State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        return resolveService().isEnabled() ? RUNNING : DISABLED;
    }

    private Void stopGitTransport(Void ignored) {
        GitNativeTransportService currentService = service;
        State currentState = stateMachine.currentState();
        if (currentService != null && (RUNNING.equals(currentState) || ERR.equals(currentState))) {
            currentService.onStop();
        }
        return Void.EMPTY;
    }

    private GitNativeTransportService resolveService() {
        if (service == null) {
            synchronized (this) {
                if (service == null) {
                    service = serviceProvider.get();
                }
            }
        }
        return service;
    }
}
