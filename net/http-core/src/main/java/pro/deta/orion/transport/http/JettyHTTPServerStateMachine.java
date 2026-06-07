package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import jakarta.inject.Provider;

/**
 * @AiRule This standalone transport adapter intentionally exposes its raw StateMachine as production API.
 */
@Singleton
public final class JettyHTTPServerStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public JettyHTTPServerStateMachine(
            Provider<JettyHTTPServer> serverProvider) {
        super("http", serverProvider);
    }
}
