package pro.deta.orion.agentd.journal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agentd.core.AgentService;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.LocalSession;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Relays bounded journal pages; only server commit replies authorize host retention. */
public final class SessionJournalRelay implements AgentService {
    private static final JournalReadLimits READ_LIMITS =
            new JournalReadLimits(256, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final AgentTransport transport;
    private final SessionRegistry registry;
    private final Supplier<Optional<ConnectionId>> connection;
    private final URI endpoint;
    private final AgentProtocolCodec codec;
    private final SessionControlClient control;
    private final int chunkBytes;
    private final Map<SessionId, Pump> pumps = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agentd-journal-relay");
        thread.setDaemon(true);
        return thread;
    });
    private Optional<ConnectionId> current = Optional.empty();
    private boolean closed;

    public SessionJournalRelay(AgentTransport transport, SessionRegistry registry,
            Supplier<Optional<ConnectionId>> connection, URI endpoint,
            AgentProtocolLimits limits, SessionControlClient control) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.codec = new AgentProtocolCodec(limits);
        this.chunkBytes = Math.min(64 * 1024, limits.maxFrameBytes());
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("journal relay is closed");
        }
        transport.onSessionMessage(this::receive);
        transport.onSignal(this::signal);
        scheduler.scheduleWithFixedDelay(this::reconcile, 0, 100, TimeUnit.MILLISECONDS);
    }

    private synchronized void reconcile() {
        if (closed) {
            return;
        }
        Optional<ConnectionId> online = connection.get();
        if (!current.equals(online)) {
            for (Pump pump : pumps.values()) {
                pump.close();
            }
            pumps.clear();
            current = online;
        }
        if (current.isEmpty()) {
            return;
        }
        Map<String, LocalSession> sessions = registry.snapshot().sessions();
        var iterator = pumps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SessionId, Pump> entry = iterator.next();
            if (!sessions.containsKey(entry.getKey().value())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        for (LocalSession session : sessions.values()) {
            SessionId id = new SessionId(session.manifest().sessionId());
            Pump existing = pumps.get(id);
            if (existing == null || existing.retryReady()) {
                Pump pump = new Pump(id, session);
                pumps.put(id, pump);
                pump.thread.start();
            }
        }
    }

    private synchronized void receive(SessionId id, AgentMessage message) {
        Pump pump = pumps.get(id);
        if (pump != null) {
            pump.receive(message);
        }
    }

    private synchronized void signal(TransportSignal signal) {
        if (signal.sessionId() != null && signal.kind() != TransportSignal.Kind.CLOSED) {
            Pump pump = pumps.get(signal.sessionId());
            if (pump != null) {
                pump.failure = "session transport disconnected";
                pump.close();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        scheduler.shutdownNow();
        for (Pump pump : pumps.values()) {
            pump.close();
        }
        pumps.clear();
    }

    private final class Pump implements Runnable {
        private final SessionId id;
        private final LocalSession session;
        private final Thread thread;
        private Optional<EventId> durable = Optional.empty();
        private Optional<EventId> offered = Optional.empty();
        private boolean received;
        private boolean stopped;
        private boolean paused;
        private volatile String failure;
        private long finishedAt;

        private Pump(SessionId id, LocalSession session) {
            this.id = id;
            this.session = session;
            thread = Thread.ofVirtual().name("agentd-journal-" + id.value()).unstarted(this);
        }

        private synchronized boolean retryReady() {
            return finishedAt != 0 && !paused
                    && System.nanoTime() - finishedAt >= TimeUnit.SECONDS.toNanos(1);
        }

        private synchronized void receive(AgentMessage message) {
            if (stopped || paused) {
                return;
            }
            if (!(message instanceof AgentMessage.SessionSync sync) || !sync.sessionId().equals(id)) {
                pause("invalid session synchronization reply");
                return;
            }
            Optional<EventId> next = sync.afterEventId();
            if (next.isPresent() && (next.orElseThrow().value() == 0 || next.orElseThrow().value() == -1)) {
                pause("invalid durable EventId");
            } else if (received && (compare(next, durable) < 0 || compare(next, offered) > 0)) {
                pause("server cursor regressed or acknowledges unsent events");
            } else {
                if (!received) {
                    offered = next;
                }
                durable = next;
                received = true;
            }
            notifyAll();
        }

        private synchronized void pause(String detail) {
            paused = true;
            failure = detail;
            notifyAll();
        }

        private synchronized void close() {
            stopped = true;
            thread.interrupt();
            transport.closeSession(id);
            notifyAll();
        }

        @Override
        public void run() {
            try {
                CompletionStage<Void> opening;
                synchronized (SessionJournalRelay.this) {
                    checkOwner();
                    opening = transport.openSession(id, ignored -> new HeadersFrame(new MetaData.Request(
                            "POST", HttpURI.from(endpoint.resolve("/agent/session/" + id.value())),
                            HttpVersion.HTTP_2, HttpFields.EMPTY), null, false));
                }
                await(opening);
                send(codec.encode(new AgentMessage.SessionOpen(id,
                        session.journal().firstAvailableEventId(), session.journal().lastAvailableEventId(),
                        session.descriptor().state())));
                Optional<EventId> cursor = awaitCursor(null);
                Optional<EventId> watermark = retentionWatermark(session.directory());
                if (compare(watermark, cursor) > 0) {
                    pause("host retention watermark is ahead of the server cursor");
                    return;
                }
                acknowledge(cursor);
                FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
                Optional<JournalReadPosition> position = Optional.empty();
                try (JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(session.directory())) {
                    while (true) {
                        checkActive();
                        JournalReadPage page = reader.readPage(session.directory(), cursor, position, READ_LIMITS);
                        for (SessionEventRecord record : page.records()) {
                            synchronized (this) {
                                checkActive();
                                offered = Optional.of(record.eventId());
                            }
                            send(record.encodedRecord().toByteArray());
                        }
                        if (!page.records().isEmpty()) {
                            EventId target = page.records().getLast().eventId();
                            do {
                                Optional<EventId> next = awaitCursor(cursor);
                                acknowledge(next);
                                cursor = next;
                            } while (cursor.orElseThrow().compareTo(target) < 0);
                        }
                        position = page.nextPosition();
                        if (page.boundary() == JournalReadBoundary.ISSUE) {
                            pause("journal failure: " + page.issue().orElseThrow().detail());
                            return;
                        }
                        if (page.boundary() != JournalReadBoundary.PAGE_LIMIT) {
                            monitor.await();
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            } finally {
                synchronized (SessionJournalRelay.this) {
                    if (pumps.get(id) == this) {
                        transport.closeSession(id);
                    }
                }
                synchronized (this) {
                    stopped = true;
                    finishedAt = System.nanoTime();
                }
            }
        }

        private synchronized void checkActive() throws InterruptedException, IOException {
            if (stopped || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (paused) {
                throw new IOException(failure);
            }
        }

        private synchronized Optional<EventId> awaitCursor(Optional<EventId> previous)
                throws InterruptedException, IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!received || previous != null && durable.equals(previous)) {
                checkActive();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("server durable cursor timed out");
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            checkActive();
            return durable;
        }

        private void acknowledge(Optional<EventId> cursor) throws IOException, InterruptedException {
            checkActive();
            if (cursor.isEmpty()) {
                return;
            }
            ControlResult result = control.send(session.manifest().control(),
                    new ControlCommand.AckJournal(cursor.orElseThrow().value()));
            if (result instanceof ControlResult.JournalAcknowledged acknowledged) {
                if (new EventId(acknowledged.acknowledgedEventId()).compareTo(cursor.orElseThrow()) > 0) {
                    pause("host retention watermark is ahead of the server cursor");
                    checkActive();
                }
            } else {
                failure = "host retention acknowledgement was not confirmed";
            }
        }

        private void send(byte[] bytes) throws Exception {
            for (int offset = 0; offset < bytes.length; offset += chunkBytes) {
                CompletionStage<Void> sending;
                synchronized (SessionJournalRelay.this) {
                    checkOwner();
                    sending = transport.sendSessionCbor(id,
                            Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkBytes)));
                }
                await(sending);
            }
        }

        private void checkOwner() throws InterruptedException, IOException {
            checkActive();
            if (pumps.get(id) != this) {
                throw new InterruptedException();
            }
        }

        private void await(CompletionStage<Void> operation) throws Exception {
            try {
                operation.toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (Exception error) {
                operation.toCompletableFuture().cancel(true);
                throw error;
            }
            checkActive();
        }
    }

    private static int compare(Optional<EventId> left, Optional<EventId> right) {
        if (left.isEmpty()) {
            return right.isEmpty() ? 0 : -1;
        }
        return right.isEmpty() ? 1 : left.orElseThrow().compareTo(right.orElseThrow());
    }

    private static Optional<EventId> retentionWatermark(Path directory) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(
                directory.resolve("control-retention-state"), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(4097);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        if (bytes.length > 4096) {
            throw new IOException("host retention state exceeds its bound");
        }
        int version = 0;
        EventId watermark = null;
        try (JsonParser parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("invalid host retention state");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("invalid host retention state field");
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (field.equals("stateVersion") || field.equals("acknowledgedEventId")) {
                    if (value != JsonToken.VALUE_NUMBER_INT) {
                        throw new IOException("invalid host retention state value");
                    }
                    if (field.equals("stateVersion")) {
                        version = parser.getIntValue();
                    } else {
                        watermark = EventId.fromUnsigned(parser.getBigIntegerValue());
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null || version != 1 || watermark == null
                    || watermark.value() == 0 || watermark.value() == -1) {
                throw new IOException("invalid host retention state");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid host retention EventId", invalid);
        }
        return Optional.of(watermark);
    }
}
