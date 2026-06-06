package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionResult;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.lifecycle.state.Void;

import javax.inject.Provider;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.state;

/**
 * @AiRule This standalone transport adapter intentionally exposes its raw StateMachine as production API.
 */
@Singleton
public final class JettyHTTPServerStateMachine extends ServiceLifecycleStateMachineAdapter {
    public static final State RUNNING = state("RUNNING");
    public static final State DISABLED = state("DISABLED");

    @Inject
    public JettyHTTPServerStateMachine(
            Provider<JettyHTTPServer> serverProvider) {
        super("http", new HttpLifecycle(serverProvider));
    }

    @Override
    @TestOnly
    public ActionBinding<Void> startAction() {
        return super.startAction();
    }

    @Override
    @TestOnly
    public ActionBinding<Void> stopAction() {
        return super.stopAction();
    }

    @Override
    public StateMachine stateMachine() {
        return super.stateMachine();
    }

    @Override
    @TestOnly
    public State currentState() {
        return super.currentState();
    }

    @Override
    @TestOnly
    public StateTransitionResult start() {
        return super.start();
    }

    @Override
    @TestOnly
    public StateTransitionResult stop() {
        return super.stop();
    }

    private static final class HttpLifecycle implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
        private final Provider<JettyHTTPServer> serverProvider;
        private volatile JettyHTTPServer server;

        private HttpLifecycle(Provider<JettyHTTPServer> serverProvider) {
            this.serverProvider = Objects.requireNonNull(serverProvider, "serverProvider");
        }

        @Override
        public void onStart() {
            resolveServer().onStart();
        }

        @Override
        public void onStop() {
            JettyHTTPServer currentServer = server;
            if (currentServer != null) {
                currentServer.onStop();
            }
        }

        @Override
        public boolean isEnabled() {
            return resolveServer().isEnabled();
        }

        @Override
        public boolean isRunning() {
            return resolveServer().isRunning();
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
}
