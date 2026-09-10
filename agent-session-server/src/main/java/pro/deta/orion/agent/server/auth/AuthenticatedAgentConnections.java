package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.server.connection.AgentControlHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Owns the one authoritative authenticated control connection for each logical agent. */
public final class AuthenticatedAgentConnections {
    private final Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher;
    private final Map<AgentId, ActiveSession> active = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public AuthenticatedAgentConnections(
            Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public AgentControlHandler.Session activate(AuthenticatedConnectionContext context) {
        Objects.requireNonNull(context, "context");
        AgentControlHandler.Session delegate = Objects.requireNonNull(
                publisher.apply(context), "authenticated session");
        ActiveSession replacement = new ActiveSession(context, delegate);
        ActiveSession previous = replace(replacement);
        if (previous != null) {
            try {
                previous.context.connection().close();
                previous.delegate.onClosed(null);
            } finally {
                previous.unlock();
            }
        }
        return replacement;
    }

    private ActiveSession replace(ActiveSession replacement) {
        while (true) {
            ActiveSession previous;
            lock.lock();
            try {
                previous = active.get(replacement.context.agentId());
                if (previous == null) {
                    active.put(replacement.context.agentId(), replacement);
                    return null;
                }
            } finally {
                lock.unlock();
            }
            previous.lock();
            boolean replaced = false;
            lock.lock();
            try {
                if (active.get(replacement.context.agentId()) == previous) {
                    previous.revoke();
                    active.put(replacement.context.agentId(), replacement);
                    replaced = true;
                    return previous;
                }
            } finally {
                lock.unlock();
                if (!replaced) {
                    previous.unlock();
                }
            }
        }
    }

    public Optional<AuthenticatedConnectionContext> active(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId");
        lock.lock();
        try {
            ActiveSession session = active.get(agentId);
            return session == null ? Optional.empty() : Optional.of(session.context);
        } finally {
            lock.unlock();
        }
    }

    private final class ActiveSession implements AgentControlHandler.Session {
        private final AuthenticatedConnectionContext context;
        private final AgentControlHandler.Session delegate;
        private final ReentrantLock callbackLock = new ReentrantLock();
        private boolean authoritative = true;

        private ActiveSession(
                AuthenticatedConnectionContext context, AgentControlHandler.Session delegate) {
            this.context = context;
            this.delegate = delegate;
        }

        @Override
        public void onMessage(AgentMessage message) {
            if (!acquireIfAuthoritative()) {
                return;
            }
            try {
                delegate.onMessage(message);
            } finally {
                unlock();
            }
        }

        @Override
        public void onClosed(Throwable failure) {
            callbackLock.lock();
            try {
                lock.lock();
                try {
                    if (!authoritative || active.get(context.agentId()) != this) {
                        return;
                    }
                    active.remove(context.agentId());
                    revoke();
                } finally {
                    lock.unlock();
                }
                delegate.onClosed(failure);
            } finally {
                unlock();
            }
        }

        private boolean acquireIfAuthoritative() {
            callbackLock.lock();
            if (authoritative) {
                return true;
            }
            callbackLock.unlock();
            return false;
        }

        private void revoke() {
            authoritative = false;
            context.revoke();
        }

        private void lock() {
            callbackLock.lock();
        }

        private void unlock() {
            callbackLock.unlock();
        }
    }
}
