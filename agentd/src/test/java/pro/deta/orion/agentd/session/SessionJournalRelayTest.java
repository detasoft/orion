package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentMessageRecord;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agentd.journal.SessionJournalRelay;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.SessionStreamRequest;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SessionJournalRelayTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(LIMITS);
    private static final SessionEventCodec EVENTS = new SessionEventCodec(AgentProtocolLimits.journalDefaults());
    @TempDir Path root;

    @Test
    void waitsForDurableCursorPreservesBytesAndResumesAfterReconnectAndRestart() throws Exception {
        LocalSession local = session("one", event(10), event(30));
        SessionRegistry registry = registry(local);
        Peer peer = new Peer();
        AtomicReference<Optional<ConnectionId>> connection = connected();
        try (SessionJournalRelay relay = relay(peer, registry, connection)) {
            relay.start();
            peer.opened();
            assertThat(peer.records).isEmpty();
            peer.sync("one", null);
            await(() -> peer.records.size() == 2);
            assertThat(peer.records).containsExactly(event(10), event(30));
            assertThat(peer.acknowledged).isEmpty();
            peer.sync("one", 10L);
            await(() -> peer.acknowledged.contains(10L));
            connection.set(Optional.empty());
            await(() -> peer.closed.contains(new SessionId("one")));
            peer.records.clear();
            connection.set(Optional.of(new ConnectionId("second")));
            peer.opened();
            peer.sync("one", 10L);
            await(() -> peer.records.size() == 1);
            assertThat(peer.records).containsExactly(event(30));
            peer.sync("one", 30L);
            await(() -> peer.acknowledged.contains(30L));
        }
        peer.records.clear();
        try (SessionJournalRelay relay = relay(peer, registry, connection)) {
            relay.start();
            peer.opened();
            peer.sync("one", 30L);
            Files.write(local.directory().resolve("00000001.cbor"), event(80), StandardOpenOption.APPEND);
            await(() -> peer.records.size() == 1);
            assertThat(peer.records).containsExactly(event(80));
        }
    }

    @Test
    void boundsUnacknowledgedPagesAndKeepsOtherSessionsMoving() throws Exception {
        LocalSession busy = session("busy", records(600));
        LocalSession quiet = session("quiet", event(90));
        Peer peer = new Peer();
        try (SessionJournalRelay relay = relay(peer, registry(busy, quiet), connected())) {
            relay.start();
            peer.opened();
            peer.opened();
            peer.sync("busy", null);
            await(() -> peer.records.size() == 256);
            peer.sync("quiet", null);
            await(() -> peer.records.size() == 257);
            assertThat(peer.records.getLast()).isEqualTo(event(90));
            peer.sync("busy", 2560L);
            await(() -> peer.records.size() == 513);
            peer.sync("busy", 5120L);
            await(() -> peer.records.size() == 601);
        }
    }

    @Test
    void pausesOnlyCorruptionOrRetentionAheadAndKeepsAckFailuresLocal() throws Exception {
        LocalSession corrupt = session("corrupt", new byte[]{(byte) 0xff});
        LocalSession ahead = session("ahead", event(30));
        Files.writeString(ahead.directory().resolve("control-retention-state"),
                "{\"stateVersion\":1,\"acknowledgedEventId\":20}");
        LocalSession healthy = session("healthy", event(10));
        Peer peer = new Peer();
        peer.failAcknowledgement = true;
        try (SessionJournalRelay relay = relay(peer, registry(corrupt, ahead, healthy), connected())) {
            relay.start();
            for (int i = 0; i < 3; i++) peer.opened();
            peer.sync("corrupt", null);
            peer.sync("ahead", 10L);
            peer.sync("healthy", null);
            await(() -> peer.closed.contains(new SessionId("ahead"))
                    && peer.closed.contains(new SessionId("corrupt")));
            await(() -> peer.records.size() == 1);
            peer.sync("healthy", 10L);
            await(() -> peer.acknowledged.contains(10L));
            Files.write(healthy.directory().resolve("00000001.cbor"), event(70), StandardOpenOption.APPEND);
            await(() -> peer.records.size() == 2);
            assertThat(peer.records).containsExactly(event(10), event(70));
        }
    }

    @Test
    void rejectsRegressingAndUnsentAcknowledgements() throws Exception {
        Peer peer = new Peer();
        Peer initial = peer;
        try (SessionJournalRelay relay = relay(peer, registry(session("one", event(10))), connected())) {
            relay.start();
            peer.opened();
            peer.sync("one", null);
            await(() -> initial.records.size() == 1);
            peer.sync("one", 20L);
            await(() -> initial.closed.contains(new SessionId("one")));
            assertThat(peer.acknowledged).isEmpty();
        }
        peer = new Peer();
        Peer second = peer;
        try (SessionJournalRelay relay = relay(peer, registry(session("two", event(30))), connected())) {
            relay.start();
            peer.opened();
            peer.sync("two", 20L);
            await(() -> second.records.size() == 1);
            peer.sync("two", 10L);
            await(() -> second.closed.contains(new SessionId("two")));
            assertThat(peer.acknowledged).doesNotContain(10L);
        }
    }

    @Test
    void waitsForACompleteTailAndChunksLargeRecordsWithoutChangingBytes() throws Exception {
        byte[] original = EVENTS.encode(new EventId(50),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[150_000])));
        LocalSession local = session("large", Arrays.copyOf(original, original.length - 1));
        Peer peer = new Peer();
        try (SessionJournalRelay relay = relay(peer, registry(local), connected())) {
            relay.start();
            peer.opened();
            peer.sync("large", null);
            assertThat(peer.records).isEmpty();
            Files.write(local.directory().resolve("00000001.cbor"),
                    new byte[]{original[original.length - 1]}, StandardOpenOption.APPEND);
            await(() -> peer.records.size() == 3);
            java.io.ByteArrayOutputStream delivered = new java.io.ByteArrayOutputStream();
            for (byte[] chunk : peer.records) {
                assertThat(chunk.length).isLessThanOrEqualTo(64 * 1024);
                delivered.write(chunk);
            }
            assertThat(delivered.toByteArray()).isEqualTo(original);
            peer.sync("large", 50L);
            await(() -> peer.acknowledged.contains(50L));
        }
    }

    @Test
    void repeatsAnAmbiguousHostAcknowledgementFromTheReconnectCursor() throws Exception {
        Peer peer = new Peer();
        peer.failAcknowledgement = true;
        AtomicReference<Optional<ConnectionId>> connection = connected();
        try (SessionJournalRelay relay = relay(peer, registry(session("one", event(20))), connection)) {
            relay.start();
            peer.opened();
            peer.sync("one", 20L);
            await(() -> peer.acknowledged.size() == 1);
            connection.set(Optional.empty());
            await(() -> peer.closed.contains(new SessionId("one")));
            peer.failAcknowledgement = false;
            connection.set(Optional.of(new ConnectionId("replacement")));
            peer.opened();
            peer.sync("one", 20L);
            await(() -> peer.acknowledged.size() == 2);
            assertThat(peer.acknowledged).containsExactly(20L, 20L);
            assertThat(peer.records).isEmpty();
        }
    }

    @Test
    void blockedSessionWritesDoNotBlockOtherSessionsOrControl() throws Exception {
        Peer peer = new Peer();
        peer.blockedSession = new SessionId("busy");
        try (SessionJournalRelay relay = relay(peer,
                registry(session("busy", event(10)), session("quiet", event(50))), connected())) {
            relay.start();
            peer.opened();
            peer.opened();
            peer.sync("busy", null);
            await(() -> peer.records.size() == 1);
            peer.sync("quiet", null);
            await(() -> peer.records.size() == 2);
            peer.sendControlCbor(CODEC.encode(new AgentMessage.RequestSessionList()))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(peer.blockedWrite.isDone()).isFalse();
            assertThat(peer.records.getLast()).isEqualTo(event(50));
        }
        await(peer.blockedWrite::isCancelled);
    }

    private SessionJournalRelay relay(Peer peer, SessionRegistry registry,
            AtomicReference<Optional<ConnectionId>> connection) {
        SessionControlClient control = new SessionControlClient(Duration.ofSeconds(1), endpoint ->
                new ControlTransportFactory.Selection.Available((ignored, request, deadline) -> {
                    ByteBuffer frame = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
                    assertThat(Short.toUnsignedInt(frame.getShort(8))).isEqualTo(7);
                    long cursor = frame.getLong(NativeControlCodec.HEADER_LENGTH);
                    peer.acknowledged.add(cursor);
                    if (peer.failAcknowledgement) {
                        return new ControlTransport.Exchange.Failed(
                                ControlResult.FailureKind.CONNECTION, true, "ambiguous ACK");
                    }
                    return new ControlTransport.Exchange.Response(NativeControlCodec.frame(0x8005, 1,
                            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(cursor).array()));
                }));
        return new SessionJournalRelay(peer, registry, connection::get, URI.create("https://localhost"),
                LIMITS, control);
    }

    private LocalSession session(String id, byte[]... events) throws Exception {
        Path directory = Files.createDirectory(root.resolve(id));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (byte[] event : events) bytes.write(event);
        Files.write(directory.resolve("00000001.cbor"), bytes.toByteArray());
        SessionManifest manifest = new SessionManifest(1, 1, 1, id, 0, 0, List.of("true"), "/", 1,
                OptionalLong.empty(), 80, 24, 80, 24, "xterm",
                new SessionManifest.Sandbox(false, "none", "fail", List.of(), List.of()),
                new ControlEndpoint(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                        "control.sock", directory.resolve("control.sock")));
        return new LocalSession(directory, manifest, HostObservation.unreachable(), JournalObservation.READABLE,
                LocalSessionState.LOST);
    }

    private static SessionRegistry registry(LocalSession... sessions) {
        SessionRegistry registry = new SessionRegistry();
        Map<String, LocalSession> entries = new HashMap<>();
        for (LocalSession session : sessions) entries.put(session.manifest().sessionId(), session);
        registry.replace(new DiscoverySnapshot(entries, Map.of()));
        return registry;
    }

    private static AtomicReference<Optional<ConnectionId>> connected() {
        return new AtomicReference<>(Optional.of(new ConnectionId("first")));
    }

    private static byte[][] records(int count) throws Exception {
        byte[][] result = new byte[count][];
        for (int i = 0; i < count; i++) result[i] = event((i + 1) * 10L);
        return result;
    }

    private static byte[] event(long id) throws Exception {
        return EVENTS.encodeOpaque(new EventId(id), 9000,
                ProtocolBytes.copyOf(new byte[]{(byte) 0x82, 1, 2}),
                List.of(ProtocolBytes.copyOf(new byte[]{(byte) 0xf6})));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class Peer implements AgentTransport {
        private final BlockingQueue<SessionId> opens = new LinkedBlockingQueue<>();
        private final List<byte[]> records = new CopyOnWriteArrayList<>();
        private final List<Long> acknowledged = new CopyOnWriteArrayList<>();
        private final Set<SessionId> closed = ConcurrentHashMap.newKeySet();
        private volatile BiConsumer<SessionId, AgentMessage> messages;
        private volatile boolean failAcknowledgement;
        private SessionId blockedSession;
        private final CompletableFuture<Void> blockedWrite = new CompletableFuture<>();

        void opened() throws Exception { assertThat(opens.poll(5, TimeUnit.SECONDS)).isNotNull(); }
        void sync(String id, Long cursor) {
            SessionId session = new SessionId(id);
            messages.accept(session, new AgentMessage.SessionSync(session,
                    cursor == null ? Optional.empty() : Optional.of(new EventId(cursor))));
        }
        public CompletionStage<Void> connect() { return CompletableFuture.completedFuture(null); }
        public CompletionStage<Void> sendControlCbor(byte[] item) { return connect(); }
        public CompletionStage<Void> openSession(SessionId id, SessionStreamRequest request) { return connect(); }
        public CompletionStage<Void> sendSessionCbor(SessionId id, byte[] item) {
            try {
                if (CODEC.decode(item) instanceof AgentMessage.SessionOpen) {
                    opens.add(id);
                    return connect();
                }
                records.add(item);
            } catch (Exception failure) { records.add(item); }
            return id.equals(blockedSession) ? blockedWrite : connect();
        }
        public void closeSession(SessionId id) { closed.add(id); }
        public void onControlOutcome(Consumer<SequenceDecodeResult.Outcome<AgentMessageRecord>> receiver) { }
        public void onSessionMessage(BiConsumer<SessionId, AgentMessage> receiver) { messages = receiver; }
        public void onSignal(Consumer<TransportSignal> receiver) { }
        public void close() { }
    }
}
