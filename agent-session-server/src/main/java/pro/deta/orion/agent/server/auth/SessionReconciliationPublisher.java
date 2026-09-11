package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.agent.server.registry.SessionRegistryException;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Publishes one authenticated session that reconciles the agent's complete session reports. */
public final class SessionReconciliationPublisher {
    private final FileSystemSessionRegistry registry;
    private final Function<AuthenticatedConnectionContext, AgentControlHandler.Session> downstream;

    public SessionReconciliationPublisher(
            FileSystemSessionRegistry registry,
            Function<AuthenticatedConnectionContext, AgentControlHandler.Session> downstream) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    public AgentControlHandler.Session publish(AuthenticatedConnectionContext context) {
        Objects.requireNonNull(context, "context");
        AgentControlHandler.Session delegate = Objects.requireNonNull(
                downstream.apply(context), "downstream session");
        return new ReconciliationSession(context, delegate);
    }

    private final class ReconciliationSession implements AuthenticatedSession {
        private final AuthenticatedConnectionContext context;
        private final AgentControlHandler.Session delegate;
        private boolean acceptingMessages = true;
        private boolean closureDelivered;
        private boolean authenticated;

        private ReconciliationSession(
                AuthenticatedConnectionContext context,
                AgentControlHandler.Session delegate) {
            this.context = context;
            this.delegate = delegate;
        }

        @Override
        public void onAuthenticated() {
            synchronized (this) {
                if (!acceptingMessages || authenticated) {
                    return;
                }
                authenticated = true;
            }
            try {
                Objects.requireNonNull(
                        context.connection().send(new AgentMessage.RequestSessionList()),
                        "session list request delivery")
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                failConnection();
                            }
                        });
            } catch (RuntimeException failure) {
                failConnection();
            }
        }

        @Override
        public void onMessage(AgentMessage message) {
            Objects.requireNonNull(message, "message");
            boolean failed = false;
            synchronized (this) {
                if (!acceptingMessages) {
                    return;
                }
                List<SessionDescriptor> reported = null;
                if (message instanceof AgentMessage.SessionList sessionList) {
                    reported = sessionList.sessions();
                } else if (message instanceof AgentMessage.SessionStatus sessionStatus) {
                    reported = List.of(sessionStatus.session());
                }
                if (reported != null) {
                    try {
                        registry.reconcile(context.agentId(), reported);
                    } catch (SessionRegistryException failure) {
                        acceptingMessages = false;
                        failed = true;
                    }
                } else {
                    delegate.onMessage(message);
                }
            }
            if (failed) {
                context.connection().close();
            }
        }

        @Override
        public void onClosed(Throwable failure) {
            synchronized (this) {
                acceptingMessages = false;
                if (closureDelivered) {
                    return;
                }
                closureDelivered = true;
            }
            delegate.onClosed(failure);
        }

        private void failConnection() {
            synchronized (this) {
                if (!acceptingMessages) {
                    return;
                }
                acceptingMessages = false;
            }
            context.connection().close();
        }
    }
}
