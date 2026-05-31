package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.config.schema.SshTransportConfig;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionEvent;
import pro.deta.orion.lifecycle.state.Void;

import javax.inject.Provider;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;

@Singleton
public final class GitSshTransportStateMachine {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final SshTransportConfig config;
    private final Provider<GitSshTransportService> serviceProvider;
    private volatile GitSshTransportService service;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startSshTransport);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopSshTransport);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public GitSshTransportStateMachine(
            SshTransportConfig config,
            Provider<GitSshTransportService> serviceProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.serviceProvider = Objects.requireNonNull(serviceProvider, "serviceProvider");
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .name("git-ssh")
                .computedState((physicalState, childStates) -> isEnabled() ? physicalState : DISABLED)
                .from(NEW).on(start).to(RUNNING).failTo(ERR)
                .from(NEW).on(stop).to(FIN).failTo(ERR)
                .from(RUNNING).on(stop).to(FIN).failTo(ERR)
                .from(ERR).on(stop).to(FIN).failTo(ERR)
                .build();
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
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

    public StateTransitionEvent start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateTransitionEvent stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private void startSshTransport(Void ignored) {
        if (isEnabled()) {
            resolveService().onStart();
        }
    }

    private void stopSshTransport(Void ignored) {
        if (isEnabled()) {
            resolveService().onStop();
        }
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
