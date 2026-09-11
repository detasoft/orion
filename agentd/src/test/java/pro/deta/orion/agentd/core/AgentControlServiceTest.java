package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.session.ChildState;
import pro.deta.orion.agentd.session.ControlEndpoint;
import pro.deta.orion.agentd.session.HostObservation;
import pro.deta.orion.agentd.session.JournalObservation;
import pro.deta.orion.agentd.session.LocalSession;
import pro.deta.orion.agentd.session.LocalSessionState;
import pro.deta.orion.agentd.session.SessionDiscovery;
import pro.deta.orion.agentd.session.SessionDiscoveryMonitor;
import pro.deta.orion.agentd.session.SessionManifest;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.session.SessionRegistryFixture;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.SessionStreamRequest;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentControlServiceTest {
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());

    @TempDir
    Path temporaryDirectory;

    @Test
    void registersCallbacksBeforeConnectingAndNegotiatesWelcome() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        AgentLaunchContext context = AgentHandshakeTest.context();
        AgentControlService service = service(transport, context, Duration.ofSeconds(1));

        service.start();

        AgentMessage.Hello hello = (AgentMessage.Hello) CODEC.decode(transport.controls.getFirst());
        assertThat(transport.callbacksPresentAtConnect).isTrue();
        assertThat(hello.authentication()).isPresent();
        assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                .isEqualTo(new ConnectionId("connection-1"));
        ReconnectToken reconnectToken = service.connection().orElseThrow().reconnectToken();
        service.close();
        assertThat(transport.closed).isTrue();
        assertThat(context.permit().copyBytes()).containsOnly(0);
        assertThat(reconnectToken.copyBytes()).containsOnly(0);
    }

    @Test
    void rejectsUnexpectedAndUnauthenticatedFirstMessages() {
        assertStartupFails(new AgentMessage.RequestSessionList());
        assertStartupFails(new AgentMessage.Welcome(
                AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                new ConnectionId("connection-1"), Map.of()));
    }

    @Test
    void transportFailureAndHandshakeTimeoutFailWithRedactedErrors() {
        FakeTransport failed = new FakeTransport();
        failed.connectFailure = new IllegalStateException("permit-secret");
        AgentControlService failureService = service(
                failed, AgentHandshakeTest.context(), Duration.ofSeconds(1));

        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(failureService::start)
                .withMessageNotContaining("permit-secret");

        AgentControlService timeoutService = service(
                new FakeTransport(), AgentHandshakeTest.context(), Duration.ZERO);
        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(timeoutService::start)
                .withMessageContaining("timed out");
    }

    @Test
    void appliesOneDeadlineAcrossConnectSendAndWelcome() {
        AtomicLong nanoTime = new AtomicLong();
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        transport.nanoTime = nanoTime;
        transport.connectElapsedNanos = Duration.ofMillis(600).toNanos();
        transport.sendElapsedNanos = Duration.ofMillis(600).toNanos();
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofSeconds(1), nanoTime::get);

        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(service::start)
                .withMessageContaining("timed out");
        assertThat(transport.closed).isTrue();
    }

    @Test
    void ignoresUnsupportedVersionOutcomeAfterHandshake() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofSeconds(1));

        service.start();
        AgentConnection established = service.connection().orElseThrow();
        AgentProtocolException unsupported = new AgentProtocolException(
                AgentProtocolException.Reason.UNSUPPORTED_VERSION, "Unsupported Agent protocol version: 2");
        transport.controlReceiver.accept(new SequenceDecodeResult.Rejected<>(
                new SequenceDecodeIssue.Recoverable(unsupported, 1)));

        assertThat(service.connection()).contains(established);
        service.close();
    }

    @Test
    void reconnectsWithTheCurrentTokenAndKeepsSendingHeartbeats() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 10));
        transport.replies.add(welcome("connection-2", (byte) 10, 10));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofMillis(100));

        service.start();
        AgentMessage.Hello initial = first(transport.controls, AgentMessage.Hello.class);
        transport.signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));

        await(() -> count(transport.controls, AgentMessage.Hello.class) == 2);
        List<AgentMessage.Hello> hellos = messages(transport.controls, AgentMessage.Hello.class);
        AgentMessage.Hello reconnect = hellos.get(1);
        assertThat(reconnect.agentId()).isEqualTo(initial.agentId());
        assertThat(reconnect.instanceId()).isEqualTo(initial.instanceId());
        assertThat(reconnect.authentication()).hasValueSatisfying(authentication -> {
            assertThat(authentication.kind()).isEqualTo(AgentAuthentication.Kind.RECONNECT_TOKEN);
            assertThat(authentication.credential().toByteArray()).containsOnly(9);
        });
        await(() -> count(transport.controls, AgentMessage.Heartbeat.class) > 0);
        assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                .isEqualTo(new ConnectionId("connection-2"));
        service.close();
    }

    @Test
    void reconnectsWhenTheInitialStreamClosesImmediatelyAfterWelcome() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));
        transport.disconnectsAfterReplies.set(1);
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofMillis(100));

        service.start();

        await(() -> count(transport.controls, AgentMessage.Hello.class) == 2);
        assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                .isEqualTo(new ConnectionId("connection-2"));
        service.close();
    }

    @Test
    void retriesReconnectFailureAndStopsAfterClose() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofMillis(25));

        service.start();
        transport.connectFailures.set(1);
        transport.signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));

        await(() -> transport.connectCount.get() >= 3);
        await(() -> count(transport.controls, AgentMessage.Hello.class) == 2);
        assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                .isEqualTo(new ConnectionId("connection-2"));
        service.close();
        int connectionsAfterClose = transport.connectCount.get();
        transport.signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(transport.connectCount).hasValue(connectionsAfterClose);
    }

    @Test
    void retriesALostReconnectWelcomeWithTheSameToken() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofMillis(25));

        service.start();
        transport.signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));
        await(() -> count(transport.controls, AgentMessage.Hello.class) == 2);
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));

        await(() -> count(transport.controls, AgentMessage.Hello.class) == 3);
        List<AgentMessage.Hello> hellos = messages(transport.controls, AgentMessage.Hello.class);
        assertThat(hellos.get(1).authentication().orElseThrow().credential())
                .isEqualTo(hellos.get(2).authentication().orElseThrow().credential());
        await(() -> service.connection().orElseThrow().connectionId().equals(new ConnectionId("connection-2")));
        service.close();
    }

    @Test
    void ignoresSessionStreamFailuresAndRejectsInvalidHeartbeatConfiguration() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofMillis(100));

        service.start();
        transport.signalReceiver.accept(new TransportSignal(
                TransportSignal.Kind.STREAM_RESET, new SessionId("session-1"), null));
        TimeUnit.MILLISECONDS.sleep(400);

        assertThat(transport.connectCount).hasValue(1);
        service.close();

        FakeTransport invalid = new FakeTransport();
        invalid.replies.add(welcome("invalid", (byte) 11, 0));
        AgentControlService invalidService = service(
                invalid, AgentHandshakeTest.context(), Duration.ofMillis(100));
        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(invalidService::start)
                .withMessageContaining("heartbeat interval");
        assertThat(invalidService.connection()).isEmpty();
    }

    @Test
    void waitsForInitialDiscoveryAndAnswersRepeatedListRequestsFromTheCompletedSnapshot() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        SessionRegistry registry = new SessionRegistry();
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofSeconds(1));
        service.start();

        transport.deliver(new AgentMessage.RequestSessionList());
        assertThat(messages(transport.controls, AgentMessage.SessionList.class)).isEmpty();

        SessionRegistryFixture.publish(registry, Map.of("session-1", session("session-1", ChildState.LIVE)));
        transport.deliver(new AgentMessage.RequestSessionList());

        assertThat(messages(transport.controls, AgentMessage.SessionList.class))
                .extracting(message -> message.sessions().stream()
                        .map(SessionDescriptor::sessionId)
                        .map(SessionId::value)
                        .toList())
                .containsExactly(List.of("session-1"), List.of("session-1"));
        service.close();
    }

    @Test
    void answersWithACompleteEmptyListAfterAnEmptyInitialScan() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        SessionRegistry registry = new SessionRegistry();
        SessionRegistryFixture.publish(registry, Map.of());
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofSeconds(1));
        service.start();

        transport.deliver(new AgentMessage.RequestSessionList());

        assertThat(messages(transport.controls, AgentMessage.SessionList.class))
                .singleElement()
                .extracting(AgentMessage.SessionList::sessions)
                .satisfies(sessions -> assertThat(sessions).isEmpty());
        service.close();
    }

    @Test
    void reportsChangedSessionsAndUsesFullRefreshesForMembershipChanges() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        SessionRegistry registry = new SessionRegistry();
        SessionRegistryFixture.publish(registry, Map.of("session-1", session("session-1", ChildState.LIVE)));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofSeconds(1));
        service.start();

        transport.deliver(new AgentMessage.RequestSessionList());
        SessionRegistryFixture.publish(registry, Map.of("session-1", session("session-1", ChildState.EXITED)));
        SessionRegistryFixture.publish(registry, Map.of(
                "session-1", session("session-1", ChildState.EXITED),
                "session-2", session("session-2", ChildState.LIVE)));
        SessionRegistryFixture.publish(registry, Map.of("session-2", session("session-2", ChildState.LIVE)));

        assertThat(messages(transport.controls, AgentMessage.SessionStatus.class))
                .extracting(message -> message.session().state())
                .containsExactly(AgentMessage.SessionState.EXITED);
        assertThat(messages(transport.controls, AgentMessage.SessionList.class))
                .extracting(message -> message.sessions().size())
                .containsExactly(1, 2, 1);
        service.close();
    }

    @Test
    void reportsInitialScanAndChangesProducedByTheDiscoveryMonitor() throws Exception {
        Path sessionsDirectory = Files.createDirectories(temporaryDirectory.resolve("sessions"));
        SessionRegistry registry = new SessionRegistry();
        AtomicReference<ChildState> childState = new AtomicReference<>(ChildState.LIVE);
        SessionDiscovery discovery = new SessionDiscovery(
                sessionsDirectory,
                directory -> session(directory.getFileName().toString(), childState.get()).manifest(),
                (directory, manifest) -> HostObservation.live(childState.get()),
                directory -> JournalObservation.READABLE,
                registry);
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofSeconds(1));

        service.start();
        transport.deliver(new AgentMessage.RequestSessionList());
        try (SessionDiscoveryMonitor monitor = new SessionDiscoveryMonitor(
                sessionsDirectory, discovery, Duration.ofMillis(25))) {
            monitor.start();
            await(() -> count(transport.controls, AgentMessage.SessionList.class) == 1);

            Path sessionDirectory = Files.createDirectory(sessionsDirectory.resolve("discovered"));
            await(() -> count(transport.controls, AgentMessage.SessionList.class) == 2);
            childState.set(ChildState.EXITED);
            Files.writeString(sessionDirectory.resolve("changed"), "changed");
            await(() -> count(transport.controls, AgentMessage.SessionStatus.class) == 1);

            assertThat(messages(transport.controls, AgentMessage.SessionList.class))
                    .extracting(message -> message.sessions().stream()
                            .map(SessionDescriptor::sessionId)
                            .map(SessionId::value)
                            .toList())
                    .containsExactly(List.of(), List.of("discovered"));
            assertThat(messages(transport.controls, AgentMessage.SessionStatus.class))
                    .singleElement()
                    .extracting(message -> message.session().state())
                    .isEqualTo(AgentMessage.SessionState.EXITED);
        } finally {
            service.close();
        }
    }

    @Test
    void dropsAnOldPendingRequestAndReportsOfflineChangesAfterReconnect() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));
        SessionRegistry registry = new SessionRegistry();
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofMillis(100));
        service.start();

        transport.deliver(new AgentMessage.RequestSessionList());
        transport.signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));
        SessionRegistryFixture.publish(registry, Map.of("offline", session("offline", ChildState.LIVE)));
        await(() -> service.connection().orElseThrow().connectionId().equals(new ConnectionId("connection-2")));
        transport.deliver(new AgentMessage.RequestSessionList());

        assertThat(messages(transport.controls, AgentMessage.SessionList.class))
                .singleElement()
                .satisfies(message -> assertThat(message.sessions())
                        .extracting(descriptor -> descriptor.sessionId().value())
                        .containsExactly("offline"));
        service.close();
    }

    @Test
    void recordsOversizedReportsAndReconnectsWithoutSendingATruncatedList() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));
        SessionRegistry registry = new SessionRegistry();
        Map<String, LocalSession> sessions = new LinkedHashMap<>();
        for (int index = 1; index <= 13; index++) {
            String sessionId = "session-" + index;
            sessions.put(sessionId, session(sessionId, ChildState.LIVE));
        }
        SessionRegistryFixture.publish(registry, sessions);
        AgentProtocolLimits smallCollections = new AgentProtocolLimits(
                AgentProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES, 12, 256 * 1024,
                AgentProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES, 64);
        AgentControlService service = service(
                transport, new AgentProtocolCodec(smallCollections), AgentHandshakeTest.context(),
                registry, Duration.ofMillis(100));
        service.start();

        transport.deliver(new AgentMessage.RequestSessionList());

        await(() -> service.lastSessionReportingFailure().isPresent());
        await(() -> transport.connectCount.get() >= 2);
        assertThat(messages(transport.controls, AgentMessage.SessionList.class)).isEmpty();
        service.close();
    }

    @Test
    void recordsControlQueueCapacityFailureAndReconnectsTheAffectedConnection() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));
        transport.reportFailures.set(1);
        SessionRegistry registry = new SessionRegistry();
        SessionRegistryFixture.publish(registry, Map.of("session-1", session("session-1", ChildState.LIVE)));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofMillis(100));
        service.start();

        transport.deliver(new AgentMessage.RequestSessionList());

        await(() -> service.lastSessionReportingFailure().isPresent());
        await(() -> transport.connectCount.get() >= 2);
        assertThat(service.lastSessionReportingFailure().orElseThrow())
                .hasMessageContaining("queue is full");
        assertThat(messages(transport.controls, AgentMessage.SessionList.class)).isEmpty();
        service.close();
    }

    @Test
    void ignoresALateSendFailureFromAReplacedConnection() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.replies.add(welcome("connection-1", (byte) 9, 1_000));
        transport.replies.add(welcome("connection-2", (byte) 10, 1_000));
        SessionRegistry registry = new SessionRegistry();
        SessionRegistryFixture.publish(registry, Map.of("session-1", session("session-1", ChildState.LIVE)));
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), registry, Duration.ofMillis(100));
        service.start();
        CompletableFuture<Void> oldSend = new CompletableFuture<>();
        transport.heldReport = oldSend;

        transport.deliver(new AgentMessage.RequestSessionList());
        transport.signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));
        await(() -> service.connection().orElseThrow().connectionId().equals(new ConnectionId("connection-2")));
        oldSend.completeExceptionally(new IllegalStateException("old connection failed"));
        TimeUnit.MILLISECONDS.sleep(200);

        assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                .isEqualTo(new ConnectionId("connection-2"));
        assertThat(service.lastSessionReportingFailure()).isEmpty();
        assertThat(transport.connectCount).hasValue(2);
        service.close();
    }

    private static void assertStartupFails(AgentMessage reply) {
        FakeTransport transport = new FakeTransport();
        transport.reply = reply;
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofSeconds(1));
        assertThatExceptionOfType(HandshakeException.class).isThrownBy(service::start);
    }

    private static AgentControlService service(
            FakeTransport transport, AgentLaunchContext context, Duration timeout) {
        return service(transport, context, new SessionRegistry(), timeout);
    }

    private static AgentControlService service(
            FakeTransport transport,
            AgentLaunchContext context,
            SessionRegistry registry,
            Duration timeout
    ) {
        return service(transport, CODEC, context, registry, timeout, System::nanoTime);
    }

    private static AgentControlService service(
            FakeTransport transport,
            AgentLaunchContext context,
            Duration timeout,
            java.util.function.LongSupplier nanoTime
    ) {
        return service(transport, CODEC, context, new SessionRegistry(), timeout, nanoTime);
    }

    private static AgentControlService service(
            FakeTransport transport,
            AgentProtocolCodec codec,
            AgentLaunchContext context,
            SessionRegistry registry,
            Duration timeout
    ) {
        return service(transport, codec, context, registry, timeout, System::nanoTime);
    }

    private static AgentControlService service(
            FakeTransport transport,
            AgentProtocolCodec codec,
            AgentLaunchContext context,
            SessionRegistry registry,
            Duration timeout,
            java.util.function.LongSupplier nanoTime
    ) {
        return new AgentControlService(
                transport, codec, new AgentHandshake(), context, "2.4.1",
                new MachineInfo("runner-1", "Linux", "aarch64"), Map.of("pty", "true"),
                registry, timeout, nanoTime);
    }

    private static LocalSession session(String sessionId, ChildState childState) {
        SessionManifest manifest = new SessionManifest(
                1, 1, 1, sessionId, 1, 2, List.of("sh"), "/workspace", 42,
                OptionalLong.of(43), 80, 24, 80, 24, "xterm-256color",
                new SessionManifest.Sandbox(false, "none", "fail", List.of(), List.of()),
                new ControlEndpoint(
                        ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                        "control.sock",
                        java.nio.file.Path.of("session", "control.sock")));
        return new LocalSession(
                java.nio.file.Path.of("session", sessionId), manifest,
                HostObservation.live(childState), JournalObservation.READABLE, LocalSessionState.LIVE);
    }

    private static AgentMessage.Welcome welcome(String connectionId, byte tokenByte, long heartbeatMillis) {
        AgentMessage.Welcome welcome = AgentHandshakeTest.welcome(connectionId, tokenByte);
        return new AgentMessage.Welcome(
                welcome.protocolVersion(), welcome.journalFormatVersion(), welcome.connectionId(),
                Map.of("heartbeatMillis", Long.toString(heartbeatMillis)), welcome.reconnectToken());
    }

    private static <T extends AgentMessage> T first(List<byte[]> controls, Class<T> type) throws Exception {
        return messages(controls, type).getFirst();
    }

    private static <T extends AgentMessage> int count(List<byte[]> controls, Class<T> type) throws Exception {
        return messages(controls, type).size();
    }

    private static <T extends AgentMessage> List<T> messages(List<byte[]> controls, Class<T> type)
            throws Exception {
        List<T> result = new ArrayList<>();
        for (byte[] control : controls) {
            AgentMessage message = CODEC.decode(control);
            if (type.isInstance(message)) {
                result.add(type.cast(message));
            }
        }
        return result;
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(condition.evaluate()).isTrue();
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    private static final class FakeTransport implements AgentTransport {
        private final List<byte[]> controls = new CopyOnWriteArrayList<>();
        private final Queue<AgentMessage> replies = new ConcurrentLinkedQueue<>();
        private final AtomicInteger connectCount = new AtomicInteger();
        private final AtomicInteger connectFailures = new AtomicInteger();
        private final AtomicInteger disconnectsAfterReplies = new AtomicInteger();
        private final AtomicInteger reportFailures = new AtomicInteger();
        private Consumer<SequenceDecodeResult.Outcome<AgentMessage>> controlReceiver;
        private Consumer<TransportSignal> signalReceiver;
        private AgentMessage reply;
        private RuntimeException connectFailure;
        private CompletableFuture<Void> heldReport;
        private AtomicLong nanoTime;
        private long connectElapsedNanos;
        private long sendElapsedNanos;
        private boolean callbacksPresentAtConnect;
        private boolean closed;

        @Override
        public CompletionStage<Void> connect() {
            connectCount.incrementAndGet();
            callbacksPresentAtConnect = controlReceiver != null && signalReceiver != null;
            advance(connectElapsedNanos);
            if (connectFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("connect failed"));
            }
            return connectFailure == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(connectFailure);
        }

        @Override
        public CompletionStage<Void> sendControlCbor(byte[] item) {
            advance(sendElapsedNanos);
            try {
                AgentMessage message = CODEC.decode(item);
                if (!(message instanceof AgentMessage.Hello)
                        && reportFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("outbound transport queue is full"));
                }
                controls.add(item.clone());
                if (message instanceof AgentMessage.Hello) {
                    AgentMessage next = replies.poll();
                    if (next != null) {
                        controlReceiver.accept(new SequenceDecodeResult.Decoded<>(next));
                        if (disconnectsAfterReplies.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                            signalReceiver.accept(new TransportSignal(TransportSignal.Kind.DISCONNECTED, null));
                        }
                    } else if (reply != null) {
                        controlReceiver.accept(new SequenceDecodeResult.Decoded<>(reply));
                    }
                } else if (heldReport != null) {
                    return heldReport;
                }
            } catch (AgentProtocolException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> sendSessionCbor(SessionId id, byte[] item) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Void> openSession(SessionId id, SessionStreamRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public void onControlOutcome(Consumer<SequenceDecodeResult.Outcome<AgentMessage>> receiver) {
            controlReceiver = receiver;
        }

        @Override
        public void onSessionMessage(BiConsumer<SessionId, AgentMessage> receiver) {
        }

        @Override
        public void onSignal(Consumer<TransportSignal> receiver) {
            signalReceiver = receiver;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void advance(long elapsedNanos) {
            if (nanoTime != null) {
                nanoTime.addAndGet(elapsedNanos);
            }
        }

        private void deliver(AgentMessage message) {
            controlReceiver.accept(new SequenceDecodeResult.Decoded<>(message));
        }
    }

}
