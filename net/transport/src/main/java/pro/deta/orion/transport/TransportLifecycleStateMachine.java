package pro.deta.orion.transport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.AggregateLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.util.Objects;

/**
 * @AiRule This aggregate facade intentionally composes child adapters through their public raw StateMachine contract.
 */
@Singleton
public final class TransportLifecycleStateMachine extends AggregateLifecycleStateMachineAdapter
        implements OrionApplicationStageEventListener {
    private final GitNativeTransportStateMachine gitNativeTransport;
    private final GitSshTransportStateMachine gitSshTransport;
    private final JettyHTTPServerStateMachine jettyHttpTransport;

    @Inject
    public TransportLifecycleStateMachine(
            GitNativeTransportStateMachine gitNativeTransport,
            GitSshTransportStateMachine gitSshTransport,
            JettyHTTPServerStateMachine jettyHttpTransport) {
        super(aggregateStateMachine(
                Objects.requireNonNull(gitNativeTransport, "gitNativeTransport"),
                Objects.requireNonNull(gitSshTransport, "gitSshTransport"),
                Objects.requireNonNull(jettyHttpTransport, "jettyHttpTransport")));
        this.gitNativeTransport = gitNativeTransport;
        this.gitSshTransport = gitSshTransport;
        this.jettyHttpTransport = jettyHttpTransport;
    }

    private static AggregateStateMachine aggregateStateMachine(
            GitNativeTransportStateMachine gitNativeTransport,
            GitSshTransportStateMachine gitSshTransport,
            JettyHTTPServerStateMachine jettyHttpTransport) {
        return AggregateLifecycleStateMachineAdapter.define("transports")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("git-native", gitNativeTransport.stateMachine())
                .child("git-ssh", gitSshTransport.stateMachine())
                .child("http", jettyHttpTransport.stateMachine())
                .buildAggregateStateMachine();
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.task(this, ApplicationState.STARTING, OrionLifecycleTasks.TRANSPORT_LIFECYCLE_START, () -> {
                    start();
                    return null;
                })
                .after(OrionLifecycleTasks.TRANSPORTS_START);
        registrar.task(this, ApplicationState.STOPPING, OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP, () -> {
            stop();
            return null;
        });
    }

    @TestOnly
    public GitNativeTransportStateMachine gitNativeTransport() {
        return gitNativeTransport;
    }

    @TestOnly
    public GitSshTransportStateMachine gitSshTransport() {
        return gitSshTransport;
    }

    @TestOnly
    public JettyHTTPServerStateMachine jettyHttpTransport() {
        return jettyHttpTransport;
    }

    @TestOnly
    public StateMachineDefinition definition() {
        return aggregateStateMachine().definition();
    }

    @Override
    @TestOnly
    public State currentState() {
        return super.currentState();
    }
}
