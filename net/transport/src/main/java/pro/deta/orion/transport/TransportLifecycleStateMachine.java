package pro.deta.orion.transport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionEvent;
import pro.deta.orion.lifecycle.state.Void;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.util.List;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StateMachineDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.state;

@Singleton
public final class TransportLifecycleStateMachine implements OrionApplicationStageEventListener {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final GitNativeTransportStateMachine gitNativeTransport;
    private final GitSshTransportStateMachine gitSshTransport;
    private final JettyHTTPServerStateMachine jettyHttpTransport;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startTransports);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopTransports);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public TransportLifecycleStateMachine(
            GitNativeTransportStateMachine gitNativeTransport,
            GitSshTransportStateMachine gitSshTransport,
            JettyHTTPServerStateMachine jettyHttpTransport) {
        this.gitNativeTransport = Objects.requireNonNull(gitNativeTransport, "gitNativeTransport");
        this.gitSshTransport = Objects.requireNonNull(gitSshTransport, "gitSshTransport");
        this.jettyHttpTransport = Objects.requireNonNull(jettyHttpTransport, "jettyHttpTransport");
        StateMachineDefinition.Builder builder = StateMachineDefinition.define()
                .name("transports")
                .computedState((physicalState, childStates) -> isAnyEnabled() ? physicalState : DISABLED);
        builder.child("git-native", gitNativeTransport.stateMachine());
        builder.child("git-ssh", gitSshTransport.stateMachine());
        builder.child("http", jettyHttpTransport.stateMachine());
        definition = defineStateMachine(builder);
        stateMachine = definition.newStateMachine();
    }

    private boolean isAnyEnabled() {
        return gitNativeTransport.isEnabled() || gitSshTransport.isEnabled() || jettyHttpTransport.isEnabled();
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        if (!isAnyEnabled()) {
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

    private StateMachineDefinition defineStateMachine(StateMachineDefinition.Builder builder) {
        return builder
                .from(NEW).on(start).to(RUNNING).failTo(ERR)
                .from(NEW).on(stop).to(FIN).failTo(ERR)
                .from(RUNNING).on(stop).to(FIN).failTo(ERR)
                .from(ERR).on(stop).to(FIN).failTo(ERR)
                .build();
    }

    public GitNativeTransportStateMachine gitNativeTransport() {
        return gitNativeTransport;
    }

    public GitSshTransportStateMachine gitSshTransport() {
        return gitSshTransport;
    }

    public JettyHTTPServerStateMachine jettyHttpTransport() {
        return jettyHttpTransport;
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    public State currentState() {
        return stateMachine.currentState();
    }

    public List<StateTransitionEvent> start() {
        return stateMachine.execute(ActionId.START, Void.EMPTY);
    }

    public List<StateTransitionEvent> stop() {
        return stateMachine.execute(ActionId.STOP, Void.EMPTY);
    }

    private void startTransports(Void ignored) {
        stateMachine.propagateSequential(ActionId.START, Void.EMPTY);
    }

    private void stopTransports(Void ignored) {
        stateMachine.propagateSequential(ActionId.STOP, Void.EMPTY);
    }
}
