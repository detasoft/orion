package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Owns the one authoritative authenticated control connection for each logical agent.
 * Availability starts at authentication and advances only from identity-bound heartbeats observed by
 * the server clock. Heartbeat expiry does not revoke the launch; recovery explicitly fences its generation.
 */
public final class AuthenticatedAgentConnections {
    public static final Duration DEFAULT_HEARTBEAT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration MAX_HEARTBEAT_DEADLINE = Duration.ofDays(1);

    private final Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher;
    private final Clock clock;
    private final Duration heartbeatDeadline;
    private final Map<AgentId, ActiveSession> active = new HashMap<>();
    private final Map<AgentId, Long> revokedThrough = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public AuthenticatedAgentConnections(
            Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher) {
        this(publisher, Clock.systemUTC(), DEFAULT_HEARTBEAT_DEADLINE);
    }

    @TestOnly
    static AuthenticatedAgentConnections withPolicy(
            Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher,
            Clock clock,
            Duration heartbeatDeadline) {
        return new AuthenticatedAgentConnections(publisher, clock, heartbeatDeadline);
    }

    private AuthenticatedAgentConnections(
            Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher,
            Clock clock,
            Duration heartbeatDeadline) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatDeadline = heartbeatDeadline(heartbeatDeadline);
    }

    public AgentControlHandler.Session activate(AuthenticatedConnectionContext context) {
        Objects.requireNonNull(context, "context");
        rejectIfRevoked(context);
        AgentControlHandler.Session delegate = Objects.requireNonNull(
                publisher.apply(context), "authenticated session");
        ActiveSession replacement = new ActiveSession(context, delegate);
        ActiveSession previous;
        try {
            previous = replace(replacement);
        } catch (IllegalStateException failure) {
            reject(context, failure);
            throw failure;
        }
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

    private static Duration heartbeatDeadline(Duration deadline) {
        Objects.requireNonNull(deadline, "heartbeatDeadline");
        if (deadline.isZero() || deadline.isNegative() || deadline.compareTo(MAX_HEARTBEAT_DEADLINE) > 0) {
            throw new IllegalArgumentException("heartbeatDeadline must be positive and at most one day");
        }
        return deadline;
    }

    private static void requireObservation(
            AuthenticatedConnectionContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Instant observedAt) {
        AuthenticatedConnectionContext.ObservationResult result = context.recordObservation(
                agentVersion, machine, capabilities, observedAt);
        if (result != AuthenticatedConnectionContext.ObservationResult.RECORDED) {
            IllegalStateException failure = new IllegalStateException(
                    "Could not record authenticated agent observation: " + result);
            reject(context, failure);
            throw failure;
        }
    }

    private void rejectIfRevoked(AuthenticatedConnectionContext context) {
        boolean revoked;
        lock.lock();
        try {
            revoked = isRevoked(context);
        } finally {
            lock.unlock();
        }
        if (revoked) {
            IllegalStateException failure = new IllegalStateException("Agent generation has been revoked");
            reject(context, failure);
            throw failure;
        }
    }

    private static void reject(
            AuthenticatedConnectionContext context, IllegalStateException failure) {
        context.revoke();
        try {
            context.connection().close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public void revokeGeneration(AgentId agentId, AgentGeneration generation) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(generation, "generation");
        ActiveSession revoked = recordRevocation(agentId, generation.value());
        if (revoked != null) {
            try {
                revoked.context.connection().close();
                revoked.delegate.onClosed(null);
            } finally {
                revoked.unlock();
            }
        }
    }

    private ActiveSession recordRevocation(AgentId agentId, long generation) {
        ActiveSession candidate;
        lock.lock();
        try {
            revokedThrough.merge(agentId, generation, Math::max);
            candidate = active.get(agentId);
        } finally {
            lock.unlock();
        }
        while (candidate != null) {
            candidate.lock();
            boolean removed = false;
            lock.lock();
            try {
                ActiveSession current = active.get(agentId);
                if (current != candidate) {
                    candidate.unlock();
                    candidate = current;
                    continue;
                }
                if (candidate.context.generation().value() <= revokedThrough.get(agentId)) {
                    active.remove(agentId);
                    candidate.revoke();
                    removed = true;
                }
            } finally {
                lock.unlock();
            }
            if (removed) {
                return candidate;
            }
            candidate.unlock();
            return null;
        }
        return null;
    }

    private boolean isRevoked(AuthenticatedConnectionContext context) {
        return context.generation().value() <= revokedThrough.getOrDefault(context.agentId(), 0L);
    }

    private ActiveSession replace(ActiveSession replacement) {
        while (true) {
            ActiveSession previous;
            lock.lock();
            try {
                if (isRevoked(replacement.context)) {
                    throw new IllegalStateException("Agent generation has been revoked");
                }
                previous = active.get(replacement.context.agentId());
                if (previous == null) {
                    observeInitial(replacement);
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
                if (isRevoked(replacement.context)) {
                    throw new IllegalStateException("Agent generation has been revoked");
                }
                if (active.get(replacement.context.agentId()) == previous) {
                    observeInitial(replacement);
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

    private void observeInitial(ActiveSession session) {
        Instant observedAt = clock.instant();
        requireObservation(
                session.context,
                session.agentVersion,
                session.machine,
                session.capabilities,
                observedAt);
        session.lastHeartbeat = observedAt;
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

    public boolean available(AgentId agentId, AgentLaunchId launchId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(launchId, "launchId");
        lock.lock();
        try {
            ActiveSession session = active.get(agentId);
            return session != null
                    && session.context.launchId().equals(launchId)
                    && clock.instant().isBefore(session.lastHeartbeat.plus(heartbeatDeadline));
        } finally {
            lock.unlock();
        }
    }

    private final class ActiveSession implements AgentControlHandler.Session {
        private final AuthenticatedConnectionContext context;
        private final AgentControlHandler.Session delegate;
        private final ReentrantLock callbackLock = new ReentrantLock();
        private volatile Instant lastHeartbeat;
        private String agentVersion;
        private MachineInfo machine;
        private Map<String, String> capabilities;
        private boolean authoritative = true;

        private ActiveSession(
                AuthenticatedConnectionContext context,
                AgentControlHandler.Session delegate) {
            this.context = context;
            this.delegate = delegate;
            this.agentVersion = context.agentVersion();
            this.machine = context.machine();
            this.capabilities = context.capabilities();
        }

        @Override
        public void onMessage(AgentMessage message) {
            if (!acquireIfAuthoritative()) {
                return;
            }
            try {
                if (!observe(message)) {
                    return;
                }
                delegate.onMessage(message);
            } finally {
                unlock();
            }
        }

        private boolean observe(AgentMessage message) {
            if (message instanceof AgentMessage.Heartbeat heartbeat) {
                if (!heartbeat.agentId().equals(context.agentId())
                        || !heartbeat.instanceId().equals(context.instanceId())) {
                    failIdentity("Heartbeat identity does not match authenticated connection");
                    return false;
                }
                Instant observedAt = clock.instant();
                if (context.recordObservation(
                        agentVersion, machine, capabilities, observedAt)
                        != AuthenticatedConnectionContext.ObservationResult.RECORDED
                        || context.renewReconnectToken()
                        != AuthenticatedConnectionContext.RenewalResult.RENEWED) {
                    failConnection(new IllegalStateException(
                            "Could not persist authenticated heartbeat"));
                    return false;
                }
                lastHeartbeat = observedAt;
            } else if (message instanceof AgentMessage.AgentStatus status) {
                if (!status.agentId().equals(context.agentId())
                        || !status.instanceId().equals(context.instanceId())) {
                    failIdentity("Agent status identity does not match authenticated connection");
                    return false;
                }
                if (context.recordObservation(
                        status.agentVersion(), status.machine(), status.capabilities(), clock.instant())
                        != AuthenticatedConnectionContext.ObservationResult.RECORDED) {
                    failConnection(new IllegalStateException(
                            "Could not persist authenticated agent status"));
                    return false;
                }
                agentVersion = status.agentVersion();
                machine = status.machine();
                capabilities = status.capabilities();
            }
            return true;
        }

        private void failIdentity(String message) {
            failConnection(new IllegalArgumentException(message));
        }

        private void failConnection(RuntimeException failure) {
            try {
                onClosed(failure);
            } finally {
                context.connection().close();
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
