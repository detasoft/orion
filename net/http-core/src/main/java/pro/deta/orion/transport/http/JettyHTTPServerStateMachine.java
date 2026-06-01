package pro.deta.orion.transport.http;

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

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.*;

@Singleton
public final class JettyHTTPServerStateMachine {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final Provider<JettyHTTPServer> serverProvider;
    private volatile JettyHTTPServer server;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startHttpTransport);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopHttpTransport);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public JettyHTTPServerStateMachine(
            Provider<JettyHTTPServer> serverProvider) {
        this.serverProvider = serverProvider;
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .name("http")
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

    private OrionStageCallResult startHttpTransport(Void ignored) {
        return resolveServer().onStart();
    }

    private State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        JettyHTTPServer currentServer = resolveServer();
        if (!currentServer.isEnabled()) {
            return DISABLED;
        }
        return currentServer.isRunning() ? RUNNING : ERR;
    }

    private Void stopHttpTransport(Void ignored) {
        JettyHTTPServer currentServer = server;
        State currentState = stateMachine.currentState();
        if (currentServer != null && (RUNNING.equals(currentState) || ERR.equals(currentState))) {
            currentServer.onStop();
        }
        return Void.EMPTY;
    }

    private JettyHTTPServer resolveServer() {
        if (server == null) {
            synchronized (this) {
                if (server == null) {
                    server = serverProvider.get();
                }
            }
        }
        return server;
    }
}
