package pro.deta.orion.agent.server.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.SessionCommandOutcome;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.command.SessionCommandService;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.journal.FileSystemSessionJournalStorage;
import pro.deta.orion.agent.server.journal.JournalStorageConfig;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.agent.server.replication.SessionReplicationService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionCommandServiceTest {
    private static final AgentLabel AGENT = new AgentLabel("agent-1");
    private static final AgentLabel OTHER = new AgentLabel("agent-2");
    private static final SessionId SESSION = new SessionId("session-1");
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec MESSAGES = new AgentProtocolCodec(LIMITS);
    private static final SessionEventCodec EVENTS =
            new SessionEventCodec(AgentProtocolLimits.journalDefaults());

    @TempDir
    Path root;

    @Test
    void oldLedgerFormatIsRejectedWithoutRewritingCommands() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            fixture.running(SESSION);
            fixture.commands.resize(AGENT, new CommandId("old-command"), SESSION, 80, 24);
            fixture.commands.close();
            Path record;
            try (var files = Files.list(root.resolve("commands"))) {
                record = files.filter(path -> path.toString().endsWith(".command")).findFirst().orElseThrow();
            }
            byte[] bytes = Files.readAllBytes(record);
            ByteBuffer.wrap(bytes).putInt(4, 1);
            Files.write(record, bytes);
            assertThatThrownBy(fixture::newCommands).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(record)).isEqualTo(bytes);
        }
    }

    @Test
    void oldDeliveryCompletionCannotOverwritePendingRedelivery() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            fixture.running(SESSION);
            RecordingConnection old = new RecordingConnection();
            old.completion = new CompletableFuture<>();
            fixture.connections.activate(context("old", old));
            CommandId command = new CommandId("resize-pending");
            fixture.commands.resize(AGENT, command, SESSION, 100, 30);
            RecordingConnection replacement = new RecordingConnection();
            replacement.completion = new CompletableFuture<>();
            fixture.connections.activate(context("replacement", replacement));
            fixture.commands.redeliver(command);
            old.completion.completeExceptionally(new IllegalStateException("late failure"));
            assertThat(fixture.commands.status(command).phase()).isEqualTo(SessionCommandService.Phase.UNKNOWN);
            replacement.completion.complete(null);
            assertThat(fixture.commands.status(command).phase()).isEqualTo(SessionCommandService.Phase.SENT);
        }
    }

    @Test
    void routesOnlyToCurrentConnectionAndReusesDurableIdentitiesAfterReopen() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            fixture.running(SESSION);
            RecordingConnection old = new RecordingConnection();
            AgentControlHandler.Session oldSession = fixture.connections.activate(context("old", old));
            CommandId inputId = new CommandId("input-1");
            var first = fixture.commands.input(AGENT, inputId, SESSION, UUID.randomUUID(),
                    ProtocolBytes.copyOf(new byte[]{1}));
            assertThat(first.operationSequence()).isEqualTo(1);
            assertThat(old.sent).hasSize(1);

            RecordingConnection replacement = new RecordingConnection();
            fixture.connections.activate(context("new", replacement));
            oldSession.onMessage(new AgentMessage.CommandResult(inputId, Optional.of(SESSION),
                    AgentMessage.CommandOutcome.REJECTED, "stale"));
            assertThat(fixture.commands.status(inputId).phase()).isEqualTo(SessionCommandService.Phase.SENT);
            assertThat(old.closed).isTrue();

            fixture.commands.close();
            fixture.commands = fixture.newCommands();
            var replay = fixture.commands.redeliver(inputId);
            assertThat(replay.operationSequence()).isEqualTo(1);
            assertThat(replacement.sent).containsExactly(old.sent.getFirst());

            var second = fixture.commands.resize(AGENT, new CommandId("resize-2"), SESSION, 90, 30);
            assertThat(second.operationSequence()).isEqualTo(2);
            assertThat(((AgentMessage.Resize) replacement.sent.get(1)).operationSequence()).isEqualTo(2);
            assertThat(fixture.commands.signal(AGENT, new CommandId("signal-3"), SESSION,
                    AgentMessage.SignalKind.INTERRUPT, -1).operationSequence()).isEqualTo(3);
            assertThat(fixture.commands.terminate(AGENT, new CommandId("terminate-4"), SESSION,
                    AgentMessage.TerminationMode.FORCE).operationSequence()).isEqualTo(4);
            assertThat(replacement.sent).hasSize(4);
        }
    }

    @Test
    void takeoverCannotReplaceAConnectionDuringCommandSendInitiation() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            CountDownLatch sending = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger oldSends = new AtomicInteger();
            AgentControlHandler.Connection old = new AgentControlHandler.Connection() {
                @Override
                public CompletionStage<Void> send(AgentMessage message) {
                    oldSends.incrementAndGet();
                    sending.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void handshakeComplete() {
                }

                @Override
                public void close() {
                }
            };
            fixture.connections.activate(context("old", old));
            RecordingConnection replacement = new RecordingConnection();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var delivery = executor.submit(() -> fixture.connections.send(
                        AGENT, new AgentMessage.RequestSessionList()));
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                var takeover = executor.submit(() -> fixture.connections.activate(
                        context("new", replacement)));
                assertThatThrownBy(() -> takeover.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                delivery.get(5, TimeUnit.SECONDS);
                takeover.get(5, TimeUnit.SECONDS);
                fixture.connections.send(AGENT, new AgentMessage.RequestSessionList());
            }
            assertThat(oldSends).hasValue(1);
            assertThat(replacement.sent).hasSize(1);
        }
    }

    @Test
    void transientRejectionDoesNotCompleteAndJournalConfirmationDoes() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            fixture.running(SESSION);
            RecordingConnection connection = new RecordingConnection();
            fixture.connections.activate(context("current", connection));
            CommandId id = new CommandId("resize-1");
            fixture.commands.resize(AGENT, id, SESSION, 100, 40);
            AgentMessage.Resize message = (AgentMessage.Resize) connection.sent.getFirst();
            fixture.commands.controlSession(AGENT).onMessage(new AgentMessage.CommandResult(
                    id, Optional.of(SESSION), AgentMessage.CommandOutcome.REJECTED, "stale sequence"));
            assertThat(fixture.commands.status(id).phase())
                    .isEqualTo(SessionCommandService.Phase.DELIVERY_FAILED);

            var result = EVENTS.decode(EVENTS.encode(new EventId(1), new SessionEventPayload.CommandResult(
                    SessionCommandSource.SERVER, 1,
                    ProtocolBytes.copyOf(MESSAGES.encode(message)),
                    SessionCommandOutcome.SUCCEEDED, "")));
            fixture.replication.append(context("journal", new RecordingConnection()), SESSION, List.of(result));
            fixture.replication.append(context("journal", new RecordingConnection()), SESSION, List.of(result));
            assertThat(fixture.commands.status(id).phase()).isEqualTo(SessionCommandService.Phase.CONFIRMED);
            assertThat(fixture.commands.status(id).outcome()).contains(SessionCommandOutcome.SUCCEEDED);
            assertThat(fixture.commands.redeliver(id).phase()).isEqualTo(SessionCommandService.Phase.CONFIRMED);
            assertThat(connection.sent).hasSize(1);
        }
    }

    @Test
    void missingResultRemainsUnknownAndJournalExitBlocksFurtherEffects() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            fixture.running(SESSION);
            CommandId signal = new CommandId("signal-1");
            fixture.commands.signal(AGENT, signal, SESSION, AgentMessage.SignalKind.INTERRUPT, -1);
            assertThat(fixture.commands.status(signal).phase())
                    .isEqualTo(SessionCommandService.Phase.DELIVERY_FAILED);
            var exit = EVENTS.decode(EVENTS.encode(new EventId(1),
                    new SessionEventPayload.ProcessExited(0)));
            fixture.replication.append(context("journal", new RecordingConnection()), SESSION, List.of(exit));
            assertThat(fixture.commands.status(signal).phase())
                    .isEqualTo(SessionCommandService.Phase.DELIVERY_FAILED);
            assertThatThrownBy(() -> fixture.commands.terminate(
                    AGENT, new CommandId("terminate-1"), SESSION, AgentMessage.TerminationMode.FORCE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validatesAgentSessionAndLifecycleBeforeReservingSequences() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            fixture.running(SESSION);
            assertThatThrownBy(() -> fixture.commands.resize(
                    OTHER, new CommandId("foreign"), SESSION, 80, 24))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.commands.resize(
                    AGENT, new CommandId("missing"), new SessionId("absent"), 80, 24))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.commands.resize(
                    AGENT, new CommandId("invalid-size"), SESSION, 0, 24))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.commands.resize(AGENT, new CommandId("valid"), SESSION, 80, 24)
                    .operationSequence()).isEqualTo(1);
            fixture.sessions.recordOutcome(AGENT, SESSION,
                    new pro.deta.orion.agent.server.registry.SessionRecord.Outcome(
                            AgentMessage.SessionState.EXITED, "done"));
            assertThatThrownBy(() -> fixture.commands.resize(
                    AGENT, new CommandId("exited"), SESSION, 80, 24))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void startHasNoNativeSequenceAndOnlyJournalEvidenceConfirmsIt() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            CommandId id = new CommandId("start-1");
            AgentMessage.StartSession start = new AgentMessage.StartSession(
                    id, SESSION, Optional.empty(), List.of("sh"), "/tmp", Map.of(),
                    80, 24, "none", "native");
            assertThat(fixture.commands.start(AGENT, start).operationSequence()).isZero();
            assertThat(fixture.sessions.find(SESSION)).get().extracting(record -> record.agentLabel())
                    .isEqualTo(AGENT);
            assertThatThrownBy(() -> fixture.sessions.reconcile(OTHER, List.of(new SessionDescriptor(
                    SESSION, AgentMessage.SessionState.RUNNING,
                    Optional.empty(), Optional.empty(), "foreign"))))
                    .isInstanceOf(pro.deta.orion.agent.server.registry.SessionRegistryException.class);
            assertThat(fixture.commands.status(id).phase())
                    .isEqualTo(SessionCommandService.Phase.DELIVERY_FAILED);
            assertThatThrownBy(() -> fixture.commands.start(AGENT, new AgentMessage.StartSession(
                    new CommandId("start-2"), SESSION, Optional.empty(), List.of("sh"), "/tmp", Map.of(),
                    80, 24, "none", "native"))).isInstanceOf(IllegalArgumentException.class);
            var unrelatedFailure = EVENTS.decode(EVENTS.encode(new EventId(1),
                    new SessionEventPayload.SessionStartFailed(
                            new CommandId("other-start"), "wrong command", 0)));
            fixture.replication.append(context("journal", new RecordingConnection()), SESSION, List.of(unrelatedFailure));
            assertThat(fixture.commands.status(id).outcome()).isEmpty();
            var started = EVENTS.decode(EVENTS.encode(new EventId(2),
                    new SessionEventPayload.ProcessStarted(123)));
            fixture.replication.append(context("journal", new RecordingConnection()), SESSION, List.of(started));
            assertThat(fixture.commands.status(id).phase()).isEqualTo(SessionCommandService.Phase.CONFIRMED);
            assertThat(fixture.commands.status(id).outcome()).contains(SessionCommandOutcome.SUCCEEDED);
        }
    }

    @Test
    void nativeStartFailureConfirmsOnlyItsCommandAndPreservesItsDiagnostic() throws Exception {
        try (Fixture fixture = new Fixture(root)) {
            CommandId id = new CommandId("failed-start");
            fixture.commands.start(AGENT, new AgentMessage.StartSession(
                    id, SESSION, Optional.empty(), List.of("missing-command"), "/tmp", Map.of(),
                    80, 24, "none", "native"));
            String diagnostic = "x".repeat(300_000);
            var failure = EVENTS.decode(EVENTS.encode(new EventId(1),
                    new SessionEventPayload.SessionStartFailed(id, diagnostic, 17)));
            fixture.replication.append(context("journal", new RecordingConnection()), SESSION, List.of(failure));

            var status = fixture.commands.status(id);
            assertThat(status.phase()).isEqualTo(SessionCommandService.Phase.CONFIRMED);
            assertThat(status.outcome()).contains(SessionCommandOutcome.FAILED);
            assertThat(status.detail()).isEqualTo(diagnostic);
        }
    }

    private static AuthenticatedConnectionContext context(
            String id, AgentControlHandler.Connection connection) {
        return new AuthenticatedConnectionContext(
                AGENT, new AgentGeneration(1), new AgentLaunchId(UUID.randomUUID()),
                new AgentInstanceId(UUID.randomUUID()), "1.0",
                new MachineInfo("host", "linux", "x86_64"), Map.of(),
                new ConnectionId(id), connection,
                () -> AuthenticatedConnectionContext.RenewalResult.RENEWED,
                (version, machine, capabilities, observedAt) ->
                        AuthenticatedConnectionContext.ObservationResult.RECORDED, () -> () -> { });
    }

    private static final class RecordingConnection implements AgentControlHandler.Connection {
        private final List<AgentMessage> sent = new ArrayList<>();
        private boolean closed;
        private CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            sent.add(message);
            return completion;
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final FileSystemAgentRegistry agents;
        private final FileSystemSessionRegistry sessions;
        private final FileSystemSessionJournalStorage journals;
        private final AuthenticatedAgentConnections connections;
        private final SessionReplicationService replication;
        private SessionCommandService commands;

        private Fixture(Path root) throws Exception {
            this.root = root;
            agents = new FileSystemAgentRegistry(root.resolve("agents"));
            agents.register(AGENT, "agent");
            agents.register(OTHER, "other");
            sessions = new FileSystemSessionRegistry(root.resolve("sessions"));
            journals = new FileSystemSessionJournalStorage(root.resolve("journals"),
                    new JournalStorageConfig(AgentProtocolLimits.journalDefaults()));
            connections = new AuthenticatedAgentConnections(ignored -> new AgentControlHandler.Session() {
                @Override
                public void onMessage(AgentMessage message) {
                }

                @Override
                public void onClosed(Throwable failure) {
                }
            });
            replication = new SessionReplicationService(journals, sessions);
            commands = newCommands();
        }

        private SessionCommandService newCommands() throws Exception {
            return new SessionCommandService(root.resolve("commands"), agents, sessions, connections, journals);
        }

        private void running(SessionId sessionId) throws Exception {
            sessions.reconcile(AGENT, List.of(new SessionDescriptor(
                    sessionId, AgentMessage.SessionState.RUNNING,
                    Optional.empty(), Optional.empty(), "")));
        }

        @Override
        public void close() throws Exception {
            commands.close();
            connections.close();
            journals.close();
            sessions.close();
            agents.close();
        }
    }
}
