package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.SessionStreamRequest;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentAssemblyTest {
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());

    @TempDir
    Path state;

    @Test
    void acquiresProcessLockBeforeConnectingTransport() throws Exception {
        AgentConfiguration configuration = configuration();
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        AgentProcessMetadata holderMetadata = new AgentProcessMetadata(
                44, 55, configuration.launchId(), configuration.generation(), "/opt/orion/agentd");

        try (AgentProcessLock holder = new AgentProcessLock(configuration.processLockFile(), holderMetadata);
             Agent agent = Agent.create(configuration, context, transport,
                     new MachineInfo("runner", "linux", "aarch64"))) {
            holder.start();
            assertThatExceptionOfType(AgentStartupException.class).isThrownBy(agent::start);
            assertThat(transport.connectCalls).isZero();
            assertThat(configuration.sessionsDirectory()).doesNotExist();
        }
    }

    @Test
    void closeBeforeStartClearsLaunchPermit() {
        AgentConfiguration configuration = configuration();
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 7);
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(secret));
        Agent agent = Agent.create(
                configuration, context, new RecordingTransport(),
                new MachineInfo("runner", "linux", "aarch64"));

        assertThat(agent.configuration().sessionHostExecutable())
                .isEqualTo(state.resolve("runtime/session-host").toAbsolutePath());
        agent.close();

        assertThat(context.permit().copyBytes()).containsOnly(0);
    }

    @Test
    void assemblesDiscoveryAndReportsItsInitialSnapshot() throws Exception {
        AgentConfiguration configuration = configuration();
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);

        try (Agent agent = Agent.create(configuration, context, transport,
                new MachineInfo("runner", "linux", "aarch64"))) {
            assertThat(configuration.sessionsDirectory()).doesNotExist();

            agent.start();
            transport.deliver(new AgentMessage.RequestSessionList());
            await(() -> transport.messages(AgentMessage.SessionList.class).size() == 1);

            assertThat(configuration.sessionsDirectory()).isDirectory();
            assertThat(transport.messages(AgentMessage.SessionList.class))
                    .singleElement()
                    .extracting(AgentMessage.SessionList::sessions)
                    .satisfies(sessions -> assertThat(sessions).isEmpty());
        }
    }

    @Test
    void routesServerStartIntoTheExistingJournalRelay() throws Exception {
        AgentConfiguration configuration = configuration();
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);

        try (Agent agent = Agent.create(configuration, context, transport,
                new MachineInfo("runner", "linux", "aarch64"))) {
            agent.start();
            SessionId id = new SessionId("missing-host");
            transport.deliver(new AgentMessage.StartSession(new CommandId("start-missing-host"), id,
                    java.util.Optional.empty(), List.of("/bin/true"), state.toString(),
                    Map.of("TERM", "xterm-256color"), 80, 24, "none", "native"));

            await(() -> !transport.sessionItems.isEmpty());
            AgentMessage.SessionOpen open = (AgentMessage.SessionOpen) CODEC.decode(
                    transport.sessionItems.getFirst());
            assertThat(open.sessionId()).isEqualTo(id);
            assertThat(open.state()).isEqualTo(AgentMessage.SessionState.FAILED);
            assertThat(open.firstAvailableEventId()).contains(new EventId(1));
            transport.sync(id, java.util.Optional.empty());
            await(() -> transport.sessionItems.size() == 2);
            SessionEventRecord event = new SessionEventCodec(AgentProtocolLimits.journalDefaults())
                    .decode(transport.sessionItems.getLast());
            assertThat(event.eventId()).isEqualTo(new EventId(1));
            assertThat(event.eventType()).isEqualTo(SessionEventType.SESSION_START_FAILED);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void routesServerCommandsThroughARealHostAndRelaysTheirJournal() throws Exception {
        Path executable = Path.of("../session-host/target/cargo/debug/session-host").toAbsolutePath();
        assertThat(executable).isRegularFile().isExecutable();
        Path shortState = Files.createTempDirectory(Path.of("/tmp"), "orion-");
        AgentConfiguration configuration = configuration(shortState, executable);
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        transport.autoSync = true;
        SessionId id = new SessionId("live-host");
        Path sessionDirectory = configuration.sessionsDirectory().resolve(id.value());

        try (Agent agent = Agent.create(configuration, context, transport,
                new MachineInfo("runner", "linux", "aarch64"))) {
            agent.start();
            transport.deliver(new AgentMessage.StartSession(new CommandId("start-live-host"), id,
                    java.util.Optional.empty(), List.of("/bin/cat"), shortState.toString(),
                    Map.of("TERM", "xterm-256color"), 80, 24, "none", "native"));
            await(() -> transport.sessionOpen(id).isPresent());
            assertThat(transport.sessionOpen(id).orElseThrow().state())
                    .isEqualTo(AgentMessage.SessionState.RUNNING);

            AgentMessage.Input input = new AgentMessage.Input(new CommandId("input-live-host"), id,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    ProtocolBytes.copyOf("orion\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1);
            transport.deliver(input);
            try {
                await(() -> transport.hasJournalEvent(SessionEventType.COMMAND_RESULT));
            } catch (AssertionError failure) {
                Path log = sessionDirectory.resolve("session-host.log");
                throw new AssertionError("command reports: "
                        + transport.messages(AgentMessage.CommandResult.class)
                        + ", journal events: " + transport.journalEvents()
                        + ", host log: " + (Files.exists(log) ? Files.readString(log) : "missing"),
                        failure);
            }
            assertThat(transport.journalEvents()).anySatisfy(event -> {
                if (event.eventType() != SessionEventType.COMMAND_RESULT) {
                    throw new AssertionError("not a command result");
                }
                SessionEventPayload.CommandResult result = (SessionEventPayload.CommandResult)
                        new SessionEventCodec(AgentProtocolLimits.journalDefaults())
                                .decodeKnownPayload(event).orElseThrow();
                assertThat(result.source()).isEqualTo(SessionCommandSource.SERVER);
                assertThat(result.operationSequence()).isEqualTo(1);
                assertThat(result.sourceEnvelope().toByteArray()).containsExactly(CODEC.encode(input));
            });

            transport.deliver(new AgentMessage.Terminate(new CommandId("stop-live-host"), id,
                    AgentMessage.TerminationMode.FORCE, 2));
            await(() -> transport.hasJournalEvent(SessionEventType.PROCESS_EXITED));
        } finally {
            try {
                stopHostIfLive(sessionDirectory);
            } finally {
                deleteTree(shortState);
            }
        }
    }

    private AgentConfiguration configuration() {
        return configuration(state, state.resolve("runtime/session-host"));
    }

    private AgentConfiguration configuration(Path stateDirectory, Path executable) {
        return new AgentConfiguration(
                URI.create("https://agent.test"), stateDirectory,
                new AgentLabel("agent-1"), new AgentGeneration(1),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                AgentProtocolLimits.defaults(), "1.0.0", executable, false);
    }

    private static void stopHostIfLive(Path sessionDirectory) throws Exception {
        if (!Files.isRegularFile(sessionDirectory.resolve("metadata"))) {
            return;
        }
        var manifest = new JsonSessionManifestReader().read(sessionDirectory);
        var host = ProcessHandle.of(manifest.hostPid());
        if (host.isEmpty() || !host.orElseThrow().isAlive()) {
            return;
        }
        new SessionControlClient(Duration.ofSeconds(1)).send(manifest.control(),
                new ControlCommand.Terminate(1, SessionCommandSource.MANUAL,
                        java.util.Optional.empty(), AgentMessage.TerminationMode.FORCE));
        try {
            host.orElseThrow().onExit().get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException failure) {
            host.orElseThrow().destroyForcibly();
            host.orElseThrow().onExit().get(2, TimeUnit.SECONDS);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static final class RecordingTransport implements AgentTransport {
        private final List<byte[]> controls = new CopyOnWriteArrayList<>();
        private final List<byte[]> sessionItems = new CopyOnWriteArrayList<>();
        private int connectCalls;
        private AgentMessage reply;
        private boolean autoSync;
        private Consumer<SequenceDecodeResult.Outcome<AgentMessageRecord>> controlReceiver;
        private BiConsumer<SessionId, AgentMessage> sessionReceiver;

        @Override
        public CompletionStage<Void> connect() {
            connectCalls++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> sendControlCbor(byte[] item) {
            controls.add(item.clone());
            try {
                if (CODEC.decode(item) instanceof AgentMessage.Hello && reply != null) {
                    controlReceiver.accept(decoded(reply));
                }
            } catch (AgentProtocolException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> sendSessionCbor(SessionId id, byte[] item) {
            sessionItems.add(item.clone());
            if (autoSync) {
                try {
                    if (CODEC.decode(item) instanceof AgentMessage.SessionOpen) {
                        sync(id, java.util.Optional.empty());
                    }
                } catch (AgentProtocolException notControl) {
                    try {
                        EventId eventId = new SessionEventCodec(AgentProtocolLimits.journalDefaults())
                                .decode(item).eventId();
                        sync(id, java.util.Optional.of(eventId));
                    } catch (AgentProtocolException failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> openSession(SessionId id, SessionStreamRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeSession(SessionId sessionId) {
        }

        @Override
        public void onControlOutcome(Consumer<SequenceDecodeResult.Outcome<AgentMessageRecord>> receiver) {
            controlReceiver = receiver;
        }

        @Override
        public void onSessionMessage(BiConsumer<SessionId, AgentMessage> receiver) {
            sessionReceiver = receiver;
        }

        @Override
        public void onSignal(Consumer<TransportSignal> receiver) {
        }

        @Override
        public void close() {
        }

        private void deliver(AgentMessage message) {
            controlReceiver.accept(decoded(message));
        }

        private void sync(SessionId id, java.util.Optional<EventId> cursor) {
            sessionReceiver.accept(id, new AgentMessage.SessionSync(id, cursor));
        }

        private java.util.Optional<AgentMessage.SessionOpen> sessionOpen(SessionId id) {
            for (byte[] item : sessionItems) {
                try {
                    if (CODEC.decode(item) instanceof AgentMessage.SessionOpen open
                            && open.sessionId().equals(id)) {
                        return java.util.Optional.of(open);
                    }
                } catch (AgentProtocolException ignored) {
                    // Journal items are not control messages.
                }
            }
            return java.util.Optional.empty();
        }

        private List<SessionEventRecord> journalEvents() throws AgentProtocolException {
            List<SessionEventRecord> events = new ArrayList<>();
            SessionEventCodec eventCodec = new SessionEventCodec(AgentProtocolLimits.journalDefaults());
            for (byte[] item : sessionItems) {
                try {
                    events.add(eventCodec.decode(item));
                } catch (AgentProtocolException ignored) {
                    // SESSION_OPEN is not a journal event.
                }
            }
            return events;
        }

        private boolean hasJournalEvent(int type) throws AgentProtocolException {
            for (SessionEventRecord event : journalEvents()) {
                if (event.eventType() == type) {
                    return true;
                }
            }
            return false;
        }

        private <T extends AgentMessage> List<T> messages(Class<T> type) throws AgentProtocolException {
            List<T> messages = new ArrayList<>();
            for (byte[] item : controls) {
                AgentMessage message = CODEC.decode(item);
                if (type.isInstance(message)) {
                    messages.add(type.cast(message));
                }
            }
            return messages;
        }
    }

    private static SequenceDecodeResult.Decoded<AgentMessageRecord> decoded(AgentMessage message) {
        try {
            return new SequenceDecodeResult.Decoded<>(
                    new AgentMessageRecord(message, ProtocolBytes.copyOf(CODEC.encode(message))));
        } catch (AgentProtocolException failure) {
            throw new AssertionError(failure);
        }
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
}
