package pro.deta.orion.component;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

/** Lifecycle adapter for the server-side Agent control state. */
@Singleton
public final class AgentSessionServerStateMachine extends ServiceLifecycleStateMachineAdapter {
    @Inject
    public AgentSessionServerStateMachine(Provider<AgentSessionServer> serverProvider) {
        super("agent-session-server", serverProvider);
    }
}
