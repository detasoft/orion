package pro.deta.orion.agentd.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agentd.session.DiscoverySnapshot;
import pro.deta.orion.agentd.session.LocalSession;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.TransportSignal;

public final class AgentControlService implements AgentService {
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration MAXIMUM_HEARTBEAT_INTERVAL = Duration.ofDays(1);

    private final AgentTransport transport;
    private final AgentProtocolCodec codec;
    private final AgentHandshake handshake;
    private final AgentLaunchContext context;
    private final String agentVersion;
    private final MachineInfo machine;
    private final Map<String, String> capabilities;
    private final Duration timeout;
    private final LongSupplier nanoTime;
    private final LongSupplier epochMillis;
    private final ControlConnectionLoop controlLoop;
    private final SessionRegistry registry;
    private final SessionRegistry.Observation registryObservation;
    private final AtomicReference<Exception> lastSessionReportingFailure = new AtomicReference<>();
    private Attempt attempt;
    private boolean closed;

    public AgentControlService(
            AgentTransport transport,
            AgentProtocolCodec codec,
            AgentHandshake handshake,
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            SessionRegistry registry
    ) {
        this(transport, codec, handshake, context, agentVersion, machine, capabilities,
                registry, DEFAULT_HANDSHAKE_TIMEOUT, System::nanoTime);
    }

    AgentControlService(
            AgentTransport transport,
            AgentProtocolCodec codec,
            AgentHandshake handshake,
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            SessionRegistry registry,
            Duration timeout
    ) {
        this(transport, codec, handshake, context, agentVersion, machine, capabilities,
                registry, timeout, System::nanoTime);
    }

    AgentControlService(
            AgentTransport transport,
            AgentProtocolCodec codec,
            AgentHandshake handshake,
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            SessionRegistry registry,
            Duration timeout,
            LongSupplier nanoTime
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.handshake = Objects.requireNonNull(handshake, "handshake");
        this.context = Objects.requireNonNull(context, "context");
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.capabilities = Map.copyOf(capabilities);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.epochMillis = System::currentTimeMillis;
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("handshake timeout must not be negative");
        }
        this.registry = Objects.requireNonNull(registry, "registry");
        this.controlLoop = new ControlConnectionLoop(
                this::reconnect, this::sendHeartbeat, nanoTime, RandomGenerator.getDefault());
        this.registryObservation = registry.observe(this::sessionsReplaced);
    }

    @Override
    public void start() throws HandshakeException {
        transport.onControlOutcome(this::receiveControl);
        transport.onSignal(this::receiveSignal);
        try {
            performHandshake(false);
            synchronized (this) {
                if (closed) {
                    throw new HandshakeException("AgentD control service closed");
                }
                controlLoop.start();
            }
        } catch (HandshakeException failure) {
            close();
            throw failure;
        }
    }

    public Optional<AgentConnection> connection() {
        return handshake.connection();
    }

    public Optional<Exception> lastSessionReportingFailure() {
        return Optional.ofNullable(lastSessionReportingFailure.get());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (attempt != null) {
            attempt.negotiated.completeExceptionally(new HandshakeException("AgentD control service closed"));
        }
        registryObservation.close();
        controlLoop.close();
        handshake.close();
        context.close();
        transport.close();
    }

    private void performHandshake(boolean reconnect) throws HandshakeException {
        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        Attempt current = new Attempt();
        synchronized (this) {
            if (closed) {
                throw new HandshakeException("AgentD control service closed");
            }
            attempt = current;
        }
        try {
            await(transport.connect(), deadline);
            AgentMessage.Hello hello = reconnect
                    ? handshake.reconnectHello(context, agentVersion, machine, capabilities)
                    : handshake.initialHello(context, agentVersion, machine, capabilities);
            await(transport.sendControlCbor(codec.encode(hello)), deadline);
            if (!reconnect) {
                context.permit().close();
            }
            await(current.negotiated, deadline);
        } catch (TimeoutException failure) {
            throw new HandshakeException("AgentD control handshake timed out", failure);
        } catch (HandshakeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new HandshakeException("AgentD control handshake failed", failure);
        } finally {
            synchronized (this) {
                if (attempt == current) {
                    attempt = null;
                }
            }
        }
    }

    private void receiveControl(SequenceDecodeResult.Outcome<AgentMessage> outcome) {
        Attempt current;
        synchronized (this) {
            current = attempt;
        }
        if (current == null || current.negotiated.isDone()) {
            receiveAuthenticatedControl(outcome);
            return;
        }
        if (outcome instanceof SequenceDecodeResult.Rejected<AgentMessage> rejected) {
            if (rejected.issue().exception().reason() == AgentProtocolException.Reason.UNSUPPORTED_VERSION) {
                current.negotiated.completeExceptionally(new HandshakeException(
                        "Server selected an unsupported protocol version", rejected.issue().exception()));
            }
            return;
        }
        AgentMessage message = ((SequenceDecodeResult.Decoded<AgentMessage>) outcome).value();
        try {
            if (!(message instanceof AgentMessage.Welcome welcome)) {
                throw new HandshakeException("First server control message is not WELCOME");
            }
            Duration interval = heartbeatInterval(welcome.configuration());
            AgentConnection connection = handshake.accept(welcome);
            synchronized (this) {
                if (attempt != current || closed) {
                    return;
                }
                controlLoop.connected(interval);
            }
            current.negotiated.complete(connection);
        } catch (Exception failure) {
            current.negotiated.completeExceptionally(failure instanceof HandshakeException
                    ? failure : new HandshakeException("Invalid server WELCOME"));
        }
    }

    private void receiveAuthenticatedControl(SequenceDecodeResult.Outcome<AgentMessage> outcome) {
        if (!(outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded)
                || !(decoded.value() instanceof AgentMessage.RequestSessionList)) {
            return;
        }
        AgentConnection expected = onlineConnection();
        if (expected == null) {
            return;
        }
        registry.readySnapshot().whenComplete((ignored, failure) -> {
            if (failure != null) {
                sessionReportingFailed(expected, failure);
            } else {
                sendSessionList(expected, registry.snapshot());
            }
        });
    }

    private void sessionsReplaced(DiscoverySnapshot previous, DiscoverySnapshot next) {
        AgentConnection expected = onlineConnection();
        if (expected == null) {
            return;
        }
        if (!previous.sessions().keySet().equals(next.sessions().keySet())) {
            sendSessionList(expected, next);
            return;
        }
        List<String> sessionIds = new ArrayList<>(next.sessions().keySet());
        sessionIds.sort(Comparator.naturalOrder());
        for (String sessionId : sessionIds) {
            LocalSession current = next.sessions().get(sessionId);
            if (!current.equals(previous.sessions().get(sessionId))) {
                sendSessionReport(expected, new AgentMessage.SessionStatus(current.descriptor()));
            }
        }
    }

    private void sendSessionList(AgentConnection expected, DiscoverySnapshot snapshot) {
        List<LocalSession> sessions = new ArrayList<>(snapshot.sessions().values());
        sessions.sort(Comparator.comparing(session -> session.manifest().sessionId()));
        List<SessionDescriptor> descriptors = new ArrayList<>(sessions.size());
        for (LocalSession session : sessions) {
            descriptors.add(session.descriptor());
        }
        sendSessionReport(expected, new AgentMessage.SessionList(descriptors));
    }

    private void sendSessionReport(AgentConnection expected, AgentMessage report) {
        if (!current(expected)) {
            return;
        }
        CompletionStage<Void> sending;
        try {
            sending = Objects.requireNonNull(
                    transport.sendControlCbor(codec.encode(report)), "session report send");
        } catch (Exception failure) {
            sessionReportingFailed(expected, failure);
            return;
        }
        sending.whenComplete((ignored, failure) -> {
            if (failure != null) {
                sessionReportingFailed(expected, failure);
            }
        });
    }

    private AgentConnection onlineConnection() {
        synchronized (this) {
            if (closed || !controlLoop.isOnline()) {
                return null;
            }
            return handshake.connection().orElse(null);
        }
    }

    private boolean current(AgentConnection expected) {
        synchronized (this) {
            return !closed && controlLoop.isOnline() && handshake.connection().orElse(null) == expected;
        }
    }

    private void sessionReportingFailed(AgentConnection expected, Throwable failure) {
        synchronized (this) {
            if (!current(expected)) {
                return;
            }
            lastSessionReportingFailure.set(failure instanceof Exception exception
                    ? exception : new IllegalStateException("session reporting failed", failure));
            controlLoop.heartbeatFailed();
        }
    }

    private void receiveSignal(TransportSignal signal) {
        if (signal.sessionId() != null || signal.kind() == TransportSignal.Kind.CONNECTED) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            controlLoop.disconnected();
            if (attempt != null) {
                attempt.negotiated.completeExceptionally(
                        new HandshakeException("AgentD transport disconnected"));
            }
        }
    }

    private void reconnect() {
        synchronized (this) {
            if (closed || controlLoop.isOnline()) {
                return;
            }
        }
        try {
            performHandshake(true);
            controlLoop.reconnectSucceeded();
        } catch (HandshakeException failure) {
            controlLoop.reconnectFailed();
        }
    }

    private void sendHeartbeat() {
        AgentConnection expected;
        synchronized (this) {
            if (closed || !controlLoop.isOnline()) {
                return;
            }
            expected = handshake.connection().orElse(null);
        }
        AgentMessage.Heartbeat heartbeat = new AgentMessage.Heartbeat(
                context.agentId(), context.instanceId(), Math.max(0, epochMillis.getAsLong()));
        CompletionStage<Void> sending;
        try {
            sending = transport.sendControlCbor(codec.encode(heartbeat));
        } catch (Exception failure) {
            heartbeatFailed(expected);
            return;
        }
        sending.whenComplete((ignored, failure) -> {
            if (failure != null) {
                heartbeatFailed(expected);
                return;
            }
            synchronized (AgentControlService.this) {
                if (!closed && controlLoop.isOnline() && handshake.connection().orElse(null) == expected) {
                    controlLoop.heartbeatSucceeded();
                }
            }
        });
    }

    private void heartbeatFailed(AgentConnection expected) {
        synchronized (this) {
            if (closed || !controlLoop.isOnline() || handshake.connection().orElse(null) != expected) {
                return;
            }
            controlLoop.heartbeatFailed();
        }
    }

    private static Duration heartbeatInterval(Map<String, String> configuration) throws HandshakeException {
        String encoded = configuration.get("heartbeatMillis");
        if (encoded == null) {
            return DEFAULT_HEARTBEAT_INTERVAL;
        }
        try {
            Duration interval = Duration.ofMillis(Long.parseLong(encoded));
            if (interval.isZero()
                    || interval.isNegative()
                    || interval.compareTo(MAXIMUM_HEARTBEAT_INTERVAL) > 0) {
                throw new HandshakeException("Server WELCOME has an invalid heartbeat interval");
            }
            return interval;
        } catch (NumberFormatException failure) {
            throw new HandshakeException("Server WELCOME has an invalid heartbeat interval", failure);
        }
    }

    private <T> T await(CompletionStage<T> operation, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException, HandshakeException {
        CompletableFuture<T> future = operation.toCompletableFuture();
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
            future.cancel(true);
            throw new TimeoutException();
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof HandshakeException handshakeFailure) {
                throw handshakeFailure;
            }
            throw failure;
        }
    }

    private static final class Attempt {
        private final CompletableFuture<AgentConnection> negotiated = new CompletableFuture<>();
    }
}
