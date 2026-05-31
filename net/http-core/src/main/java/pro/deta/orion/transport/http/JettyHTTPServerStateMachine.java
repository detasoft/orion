package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionEvent;
import pro.deta.orion.lifecycle.state.Void;

import javax.inject.Provider;

import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;

@Singleton
public final class JettyHTTPServerStateMachine {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    private final HttpTransportConfig httpConfig;
    private final HttpsTransportConfig httpsConfig;
    private final Provider<JettyHTTPServer> serverProvider;
    private volatile JettyHTTPServer server;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startHttpTransport);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopHttpTransport);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public JettyHTTPServerStateMachine(
            OrionConfiguration configuration,
            Provider<JettyHTTPServer> serverProvider) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        this.httpConfig = transport != null ? transport.getHttp() : null;
        this.httpsConfig = transport != null ? transport.getHttps() : null;
        this.serverProvider = serverProvider;
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .name("http")
                .computedState((physicalState, childStates) -> isEnabled() ? physicalState : DISABLED)
                .from(NEW).on(start).to(RUNNING).failTo(ERR)
                .from(NEW).on(stop).to(FIN).failTo(ERR)
                .from(RUNNING).on(stop).to(FIN).failTo(ERR)
                .from(ERR).on(stop).to(FIN).failTo(ERR)
                .build();
    }

    public boolean isEnabled() {
        return (httpConfig != null && httpConfig.isEnabled())
                || (httpsConfig != null && httpsConfig.isEnabled());
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

    private void startHttpTransport(Void ignored) {
        if (isEnabled()) {
            resolveServer().onStart();
        }
    }

    private void stopHttpTransport(Void ignored) {
        if (isEnabled()) {
            resolveServer().onStop();
        }
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
