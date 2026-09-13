package pro.deta.orion.agentd.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
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
import pro.deta.orion.agent.protocol.AgentMessageRecord;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agentd.journal.SessionJournalRelay;
import pro.deta.orion.agentd.runtime.SessionLaunchResult;
import pro.deta.orion.agentd.runtime.SessionRuntime;
import pro.deta.orion.agentd.runtime.SessionSpec;
import pro.deta.orion.agentd.runtime.WorkspaceReference;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.DiscoverySnapshot;
import pro.deta.orion.agentd.session.EstablishedSessionCommandDelivery;
import pro.deta.orion.agentd.session.FileSystemJournalProbe;
import pro.deta.orion.agentd.session.LocalSession;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.TransportSignal;

public final class AgentControlService implements AgentService {
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration MAXIMUM_HEARTBEAT_INTERVAL = Duration.ofDays(1);
    private static final Duration SESSION_CONTROL_TIMEOUT = Duration.ofSeconds(2);

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
    private final SessionCommandLanes commandLanes = new SessionCommandLanes(4, 64, 32);
    private final EstablishedSessionCommandDelivery commandDelivery;
    private volatile SessionStartConfiguration sessionStart;
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
        this.commandDelivery = new EstablishedSessionCommandDelivery(
                registry, new SessionControlClient(SESSION_CONTROL_TIMEOUT));
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
        return Optional.ofNullable(onlineConnection());
    }

    public Optional<Exception> lastSessionReportingFailure() {
        return Optional.ofNullable(lastSessionReportingFailure.get());
    }

    void configureSessionStart(SessionRuntime runtime, SessionJournalRelay relay, Path sessionsDirectory) {
        synchronized (this) {
            if (attempt != null || closed || sessionStart != null) {
                throw new IllegalStateException("session start must be configured before service start");
            }
            sessionStart = new SessionStartConfiguration(
                    Objects.requireNonNull(runtime, "runtime"),
                    Objects.requireNonNull(relay, "relay"),
                    Objects.requireNonNull(sessionsDirectory, "sessionsDirectory")
                            .toAbsolutePath().normalize());
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (attempt != null) {
                attempt.negotiated.completeExceptionally(
                        new HandshakeException("AgentD control service closed"));
            }
            registryObservation.close();
            controlLoop.close();
            handshake.close();
            context.close();
        }
        try {
            commandLanes.close();
        } finally {
            transport.close();
        }
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

    private void receiveControl(SequenceDecodeResult.Outcome<AgentMessageRecord> outcome) {
        Attempt current;
        synchronized (this) {
            current = attempt;
        }
        if (current == null || current.negotiated.isDone()) {
            receiveAuthenticatedControl(outcome);
            return;
        }
        if (outcome instanceof SequenceDecodeResult.Rejected<AgentMessageRecord> rejected) {
            if (rejected.issue().exception().reason() == AgentProtocolException.Reason.UNSUPPORTED_VERSION) {
                current.negotiated.completeExceptionally(new HandshakeException(
                        "Server selected an unsupported protocol version", rejected.issue().exception()));
            }
            return;
        }
        AgentMessage message = ((SequenceDecodeResult.Decoded<AgentMessageRecord>) outcome).value().message();
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
                commandLanes.connected();
            }
            current.negotiated.complete(connection);
        } catch (Exception failure) {
            current.negotiated.completeExceptionally(failure instanceof HandshakeException
                    ? failure : new HandshakeException("Invalid server WELCOME"));
        }
    }

    private void receiveAuthenticatedControl(SequenceDecodeResult.Outcome<AgentMessageRecord> outcome) {
        if (!(outcome instanceof SequenceDecodeResult.Decoded<AgentMessageRecord> decoded)) {
            return;
        }
        AgentMessageRecord record = decoded.value();
        SessionId commandSession = commandSession(record.message());
        if (commandSession != null) {
            AgentConnection expected = onlineConnection();
            if (expected != null) {
                CompletionStage<SessionCommandLanes.Outcome<Optional<AgentMessage.CommandResult>>> delivery =
                        commandLanes.submit(commandSession, () -> {
                            registry.readySnapshot().toCompletableFuture().get();
                            if (!current(expected)) {
                                return Optional.empty();
                            }
                            if (record.message() instanceof AgentMessage.StartSession start) {
                                return deliverStart(start);
                            }
                            return nativeCommandReport(record.message(), commandDelivery.deliver(record));
                        });
                delivery.whenComplete((result, failure) ->
                        reportCommandDelivery(expected, record.message(), result, failure));
            }
            return;
        }
        if (!(record.message() instanceof AgentMessage.RequestSessionList)) {
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

    private static SessionId commandSession(AgentMessage message) {
        return switch (message) {
            case AgentMessage.StartSession start -> start.sessionId();
            case AgentMessage.Input input -> input.sessionId();
            case AgentMessage.Resize resize -> resize.sessionId();
            case AgentMessage.Signal signal -> signal.sessionId();
            case AgentMessage.Terminate terminate -> terminate.sessionId();
            default -> null;
        };
    }

    private void reportCommandDelivery(
            AgentConnection expected,
            AgentMessage message,
            SessionCommandLanes.Outcome<Optional<AgentMessage.CommandResult>> result,
            Throwable failure
    ) {
        if (!current(expected)) {
            return;
        }
        if (failure != null) {
            sendSessionReport(expected, commandReport(message, AgentMessage.CommandOutcome.FAILED,
                    "command delivery failed"));
        } else if (result instanceof SessionCommandLanes.Outcome.Discarded<?> discarded) {
            sendSessionReport(expected, commandReport(message, AgentMessage.CommandOutcome.REJECTED,
                    "command delivery " + discarded.reason().name().toLowerCase(java.util.Locale.ROOT)));
        } else {
            ((SessionCommandLanes.Outcome.Completed<Optional<AgentMessage.CommandResult>>) result)
                    .value().ifPresent(report -> sendSessionReport(expected, report));
        }
    }

    private static Optional<AgentMessage.CommandResult> nativeCommandReport(
            AgentMessage message, ControlResult nativeResult) {
        if (nativeResult instanceof ControlResult.Rejected rejected) {
            return Optional.of(commandReport(message, AgentMessage.CommandOutcome.REJECTED,
                    "native command rejected with code " + rejected.errorCode()));
        }
        if (nativeResult instanceof ControlResult.Failed failed) {
            return Optional.of(commandReport(message, AgentMessage.CommandOutcome.FAILED,
                    "native command delivery " + failed.kind().name().toLowerCase(java.util.Locale.ROOT)));
        }
        return Optional.empty();
    }

    private static CommandId commandId(AgentMessage message) {
        return switch (message) {
            case AgentMessage.StartSession start -> start.commandId();
            case AgentMessage.Input input -> input.commandId();
            case AgentMessage.Resize resize -> resize.commandId();
            case AgentMessage.Signal signal -> signal.commandId();
            case AgentMessage.Terminate terminate -> terminate.commandId();
            default -> throw new IllegalArgumentException("message is not a session command");
        };
    }

    private Optional<AgentMessage.CommandResult> deliverStart(AgentMessage.StartSession start) {
        SessionStartConfiguration configured = Objects.requireNonNull(
                sessionStart, "session start configuration");
        Path directory = configured.sessionsDirectory().resolve(start.sessionId().value()).normalize();
        if (configured.relay().hasPendingStartFailure(start.sessionId())) {
            return Optional.empty();
        }
        if (registry.snapshot().sessions().containsKey(start.sessionId().value())
                || Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(commandReport(start, AgentMessage.CommandOutcome.REJECTED,
                    "session already exists"));
        }

        SessionSpec spec;
        try {
            spec = startSpec(start);
        } catch (IllegalArgumentException failure) {
            return preJournalStartFailure(start, configured.relay(), "invalid start specification");
        }
        SessionLaunchResult result;
        try {
            result = configured.runtime().launch(spec);
        } catch (RuntimeException failure) {
            return Optional.of(commandReport(start, AgentMessage.CommandOutcome.FAILED,
                    "session runtime failed ambiguously"));
        }
        if (result instanceof SessionLaunchResult.Started) {
            return Optional.empty();
        }
        SessionLaunchResult.Failed failed = (SessionLaunchResult.Failed) result;
        if (failed.kind() == SessionLaunchResult.FailureKind.SESSION_EXISTS) {
            return Optional.of(commandReport(start, AgentMessage.CommandOutcome.REJECTED,
                    "session already exists"));
        }
        try {
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    && new FileSystemJournalProbe().probe(directory).readable()) {
                return Optional.empty();
            }
        } catch (IOException failure) {
            return Optional.of(commandReport(start, AgentMessage.CommandOutcome.FAILED,
                    "session journal could not be inspected"));
        }
        if (failed.kind() == SessionLaunchResult.FailureKind.CLEANUP_FAILED) {
            return Optional.of(commandReport(start, AgentMessage.CommandOutcome.FAILED,
                    "session cleanup is unconfirmed"));
        }
        return preJournalStartFailure(start, configured.relay(), failed.detail());
    }

    private static Optional<AgentMessage.CommandResult> preJournalStartFailure(
            AgentMessage.StartSession start, SessionJournalRelay relay, String detail) {
        try {
            if (relay.registerStartFailure(start.sessionId(), start.commandId(), detail)) {
                return Optional.empty();
            }
        } catch (AgentProtocolException failure) {
            return Optional.of(commandReport(start, AgentMessage.CommandOutcome.FAILED,
                    "start failure journal could not be encoded"));
        }
        return Optional.of(commandReport(start, AgentMessage.CommandOutcome.FAILED,
                "start failure journal has no capacity"));
    }

    private static SessionSpec startSpec(AgentMessage.StartSession start) {
        if (!"native".equals(start.runtime())) {
            throw new IllegalArgumentException("unsupported runtime");
        }
        if (start.sessionId().value().indexOf(':') >= 0) {
            throw new IllegalArgumentException("session ID is not supported by the native host");
        }
        if (start.workspaceId().isPresent()) {
            throw new IllegalArgumentException("managed workspaces are not supported by the native runtime");
        }
        Path workingDirectory = Path.of(start.workingDirectory());
        if (!workingDirectory.isAbsolute() || !Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("working directory must be an existing absolute directory");
        }
        for (String argument : start.command()) {
            if (argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("child command contains a NUL byte");
            }
        }
        Map<String, String> environment = start.environment();
        for (String key : environment.keySet()) {
            if (!"TERM".equals(key) && !"COLORTERM".equals(key)) {
                throw new IllegalArgumentException("unsupported environment entry");
            }
        }
        String terminalType = environment.getOrDefault("TERM", "xterm-256color");
        Optional<String> colorTerminal = Optional.ofNullable(environment.get("COLORTERM"));
        if (!nativeTerminalValue(terminalType)
                || colorTerminal.filter(value -> !nativeTerminalValue(value)).isPresent()) {
            throw new IllegalArgumentException("invalid terminal environment value");
        }
        SessionSpec.Sandbox sandbox;
        if ("none".equals(start.sandboxPolicy())) {
            sandbox = SessionSpec.Sandbox.none();
        } else {
            Path policy = Path.of(start.sandboxPolicy());
            if (!policy.isAbsolute() || !Files.isRegularFile(policy, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(policy)) {
                throw new IllegalArgumentException("sandbox policy must be an absolute readable regular file");
            }
            sandbox = new SessionSpec.Sandbox(Optional.of(policy));
        }
        return new SessionSpec(start.sessionId(), start.commandId(), start.command(),
                new WorkspaceReference.ExistingDirectory(workingDirectory),
                Map.of(), start.columns(), start.rows(), terminalType, colorTerminal, sandbox);
    }

    private static boolean nativeTerminalValue(String value) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 1 && bytes <= 128 && value.indexOf('=') < 0 && value.indexOf('\0') < 0;
    }

    private static AgentMessage.CommandResult commandReport(
            AgentMessage message, AgentMessage.CommandOutcome outcome, String detail) {
        return new AgentMessage.CommandResult(
                commandId(message), Optional.of(commandSession(message)), outcome, detail);
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
            commandLanes.disconnected();
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
            commandLanes.disconnected();
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
            commandLanes.disconnected();
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

    private record SessionStartConfiguration(
            SessionRuntime runtime, SessionJournalRelay relay, Path sessionsDirectory) {
    }
}
