package pro.deta.orion.transport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.util.List;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.state;

@Singleton
public final class TransportLifecycleStateMachine implements OrionApplicationStageEventListener {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final GitNativeTransportStateMachine gitNativeTransport;
    private final GitSshTransportStateMachine gitSshTransport;
    private final JettyHTTPServerStateMachine jettyHttpTransport;
    private final StateMachineDefinition definition;
    private final AggregateStateMachine aggregateStateMachine;

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
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL);
        builder.child("git-native", gitNativeTransport.stateMachine());
        builder.child("git-ssh", gitSshTransport.stateMachine());
        builder.child("http", jettyHttpTransport.stateMachine());
        definition = defineStateMachine(builder);
        aggregateStateMachine = new AggregateStateMachine(definition);
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
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
                .from(NEW, DISABLED).on(ActionId.START).to(DISABLED, RUNNING, ERR).post(this::resolveStartState)
                .from(NEW, DISABLED).on(ActionId.STOP).to(FIN, ERR)
                .from(RUNNING).on(ActionId.STOP).to(FIN, ERR)
                .from(ERR).on(ActionId.STOP).to(FIN, ERR)
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
        return aggregateStateMachine.stateMachine();
    }

    public State currentState() {
        return aggregateStateMachine.currentState();
    }

    public List<StateTransitionResult> start() {
        return aggregateStateMachine.start();
    }

    public List<StateTransitionResult> stop() {
        return aggregateStateMachine.stop();
    }

    private State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        for (State childState : aggregateStateMachine.childStates().values()) {
            if (ERR.equals(childState)) {
                return ERR;
            }
            if (RUNNING.equals(childState)) {
                return RUNNING;
            }
        }
        return DISABLED;
    }
}
