package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Owns the one authoritative authenticated control connection for each logical agent.
 * Availability starts at authentication and advances only from identity-bound heartbeats observed by
 * the server clock. Heartbeat expiry does not revoke the launch; durable registration replacement fences its instance.
 */
public final class AuthenticatedAgentConnections implements AutoCloseable {
    public static final Duration DEFAULT_HEARTBEAT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration MAX_HEARTBEAT_DEADLINE = Duration.ofDays(1);

    private final Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher;
    private final Clock clock;
    private final Duration heartbeatDeadline;
    private final Map<AgentLabel, ActiveSession> active = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();
    private boolean closed;

    public AuthenticatedAgentConnections(
            Function<AuthenticatedConnectionContext, AgentControlHandler.Session> publisher) {
        this(publisher, Clock.systemUTC(), DEFAULT_HEARTBEAT_DEADLINE);
    }

    @TestOnly
    public static AuthenticatedAgentConnections withPolicy(
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
        try (var ignored = context.acquireAuthority()) {
            return activateCurrent(context);
        }
    }

    private AgentControlHandler.Session activateCurrent(AuthenticatedConnectionContext context) {
        rejectIfClosed(context);
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

    private void rejectIfClosed(AuthenticatedConnectionContext context) {
        boolean revoked;
        lock.lock();
        try {
            revoked = closed;
        } finally {
            lock.unlock();
        }
        if (revoked) {
            IllegalStateException failure = new IllegalStateException("Agent connections are closed");
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

    @Override
    public void close() {
        List<ActiveSession> closing;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            closing = List.copyOf(active.values());
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
        RuntimeException failure = null;
        for (ActiveSession session : closing) {
            try {
                session.context.connection().close();
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
            try {
                session.onClosed(null);
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException append(RuntimeException previous, RuntimeException failure) {
        if (previous == null) {
            return failure;
        }
        previous.addSuppressed(failure);
        return previous;
    }

    private ActiveSession replace(ActiveSession replacement) {
        while (true) {
            ActiveSession previous;
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("Agent connections are closed");
                }
                previous = active.get(replacement.context.agentLabel());
                if (previous == null) {
                    observeInitial(replacement);
                    active.put(replacement.context.agentLabel(), replacement);
                    stateChanged.signalAll();
                    return null;
                }
            } finally {
                lock.unlock();
            }
            previous.lock();
            boolean replaced = false;
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("Agent connections are closed");
                }
                if (active.get(replacement.context.agentLabel()) == previous) {
                    observeInitial(replacement);
                    previous.revoke();
                    active.put(replacement.context.agentLabel(), replacement);
                    stateChanged.signalAll();
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

    public Optional<AuthenticatedConnectionContext> active(AgentLabel agentLabel) {
        Objects.requireNonNull(agentLabel, "agentLabel");
        lock.lock();
        try {
            ActiveSession session = active.get(agentLabel);
            return session == null ? Optional.empty() : Optional.of(session.context);
        } finally {
            lock.unlock();
        }
    }

    /** Initiates a send only while the selected connection remains authoritative. */
    public Optional<Delivery> send(AgentLabel agentLabel, AgentMessage message) {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(message, "message");
        ActiveSession candidate;
        lock.lock();
        try {
            candidate = active.get(agentLabel);
        } finally {
            lock.unlock();
        }
        if (candidate == null) {
            return Optional.empty();
        }
        try (var ignored = candidate.context.acquireAuthority()) {
            candidate.lock();
            try {
                lock.lock();
                try {
                    if (closed || active.get(agentLabel) != candidate || !candidate.authoritative
                            || !available(agentLabel, clock.instant())) {
                        return Optional.empty();
                    }
                } finally {
                    lock.unlock();
                }
                return Optional.of(new Delivery(candidate.context,
                        Objects.requireNonNull(candidate.context.connection().send(message), "command send")));
            } finally {
                candidate.unlock();
            }
        } catch (IllegalStateException rejected) {
            return Optional.empty();
        }
    }

    public record Delivery(AuthenticatedConnectionContext context, CompletionStage<Void> completion) {
    }

    public boolean available(AgentLabel agentLabel, AgentLaunchId launchId) {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(launchId, "launchId");
        lock.lock();
        try {
            return available(agentLabel, launchId, clock.instant());
        } finally {
            lock.unlock();
        }
    }

    boolean awaitOnline(AgentLabel agentLabel, AgentLaunchId launchId, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(launchId, "launchId");
        long remaining = timeoutNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!available(agentLabel, launchId, clock.instant())) {
                if (closed) {
                    return false;
                }
                if (remaining <= 0) {
                    return false;
                }
                remaining = stateChanged.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    boolean awaitSustainedOffline(AgentLabel agentLabel, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        long remaining = timeoutNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (closed) {
                return false;
            }
            if (available(agentLabel, clock.instant())) {
                return false;
            }
            while (remaining > 0) {
                remaining = stateChanged.awaitNanos(remaining);
                if (closed || available(agentLabel, clock.instant())) {
                    return false;
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean available(AgentLabel agentLabel, AgentLaunchId launchId, Instant now) {
        ActiveSession session = active.get(agentLabel);
        return !closed
                && session != null
                && session.context.launchId().equals(launchId)
                && now.isBefore(session.lastHeartbeat.plus(heartbeatDeadline));
    }

    private boolean available(AgentLabel agentLabel, Instant now) {
        ActiveSession session = active.get(agentLabel);
        return !closed && session != null && now.isBefore(session.lastHeartbeat.plus(heartbeatDeadline));
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private final class ActiveSession implements AuthenticatedSession {
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
            try (var ignored = context.acquireAuthority()) {
                onCurrentMessage(message);
            } catch (IllegalStateException rejected) {
                context.connection().close();
            }
        }

        private void onCurrentMessage(AgentMessage message) {
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

        @Override
        public void onAuthenticated() {
            try (var ignored = context.acquireAuthority()) {
                authenticateCurrent();
            } catch (IllegalStateException rejected) {
                context.connection().close();
            }
        }

        private void authenticateCurrent() {
            if (!acquireIfAuthoritative()) {
                return;
            }
            try {
                if (delegate instanceof AuthenticatedSession authenticatedSession) {
                    authenticatedSession.onAuthenticated();
                }
            } finally {
                unlock();
            }
        }

        private boolean observe(AgentMessage message) {
            if (message instanceof AgentMessage.Heartbeat heartbeat) {
                if (!heartbeat.agentLabel().equals(context.agentLabel())
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
                signalStateChanged();
            } else if (message instanceof AgentMessage.AgentStatus status) {
                if (!status.agentLabel().equals(context.agentLabel())
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
                    if (!authoritative || active.get(context.agentLabel()) != this) {
                        return;
                    }
                    active.remove(context.agentLabel());
                    revoke();
                    stateChanged.signalAll();
                } finally {
                    lock.unlock();
                }
                delegate.onClosed(failure);
            } finally {
                unlock();
            }
        }

        private void signalStateChanged() {
            lock.lock();
            try {
                stateChanged.signalAll();
            } finally {
                lock.unlock();
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
