package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionResult;
import pro.deta.orion.lifecycle.state.StateMachineEventSubscriber;
import pro.deta.orion.lifecycle.state.StateMachineSubscription;
import pro.deta.orion.lifecycle.state.TestOnly;
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

    @TestOnly
    public StateMachineDefinition definition() {
        return definition;
    }

    @TestOnly
    public GitNativeTransportService service() {
        return resolveService();
    }

    @TestOnly
    public ActionBinding<Void> startAction() {
        return start;
    }

    @TestOnly
    public ActionBinding<Void> stopAction() {
        return stop;
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    @TestOnly
    public State currentState() {
        return stateMachine.currentState();
    }

    @TestOnly
    public String describe() {
        return stateMachine.describe();
    }

    @TestOnly
    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        return stateMachine.subscribe(subscriber);
    }

    @TestOnly
    public StateTransitionResult start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    @TestOnly
    public StateTransitionResult stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private Void startGitTransport(Void ignored) {
        resolveService().onStart();
        return Void.EMPTY;
    }

    private State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        GitNativeTransportService currentService = resolveService();
        if (!currentService.isEnabled()) {
            return DISABLED;
        }
        return currentService.isRunning() ? RUNNING : ERR;
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
