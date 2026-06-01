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
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.lifecycle.state.Void;

import javax.inject.Provider;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.*;

@Singleton
public final class GitSshTransportStateMachine {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final Provider<GitSshTransportService> serviceProvider;
    private volatile GitSshTransportService service;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startSshTransport);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopSshTransport);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public GitSshTransportStateMachine(
            Provider<GitSshTransportService> serviceProvider) {
        this.serviceProvider = Objects.requireNonNull(serviceProvider, "serviceProvider");
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .name("git-ssh")
                .from(NEW, DISABLED).on(start).to(DISABLED, RUNNING, ERR).post(this::resolveStartState)
                .from(NEW, DISABLED).on(stop).to(FIN, ERR)
                .from(RUNNING).on(stop).to(FIN, ERR)
                .from(ERR).on(stop).to(FIN, ERR)
                .build();
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
    public StateTransitionResult start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    @TestOnly
    public StateTransitionResult stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private OrionStageCallResult startSshTransport(Void ignored) {
        return resolveService().onStart();
    }

    private State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        GitSshTransportService currentService = resolveService();
        if (!currentService.isEnabled()) {
            return DISABLED;
        }
        return currentService.isRunning() ? RUNNING : ERR;
    }

    private Void stopSshTransport(Void ignored) {
        GitSshTransportService currentService = service;
        State currentState = stateMachine.currentState();
        if (currentService != null && (RUNNING.equals(currentState) || ERR.equals(currentState))) {
            currentService.onStop();
        }
        return Void.EMPTY;
    }

    private GitSshTransportService resolveService() {
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
