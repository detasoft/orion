package pro.deta.orion.agent.server.command;

import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agent.protocol.SessionCommandOutcome;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionEventType;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.auth.AuthenticatedAgentConnections;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.journal.JournalStorageException;
import pro.deta.orion.agent.server.journal.SessionJournalStorage;
import pro.deta.orion.agent.server.registry.AgentRegistryException;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.agent.server.registry.SessionRecord;
import pro.deta.orion.agent.server.registry.SessionRegistryException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Routes durable command identities while the journal remains the sole completion authority. */
public final class SessionCommandService implements AutoCloseable {
    private final FileSystemCommandLedger ledger;
    private final FileSystemAgentRegistry agents;
    private final FileSystemSessionRegistry sessions;
    private final AuthenticatedAgentConnections connections;
    private final SessionJournalStorage journals;
    private final SessionEventCodec eventCodec = new SessionEventCodec(AgentProtocolLimits.journalDefaults());
    private final Map<CommandId, Status> transientStatuses = new ConcurrentHashMap<>();

    public SessionCommandService(
            Path ledgerRoot,
            FileSystemAgentRegistry agents,
            FileSystemSessionRegistry sessions,
            AuthenticatedAgentConnections connections,
            SessionJournalStorage journals) throws IOException {
        ledger = new FileSystemCommandLedger(ledgerRoot);
        this.agents = Objects.requireNonNull(agents, "agents");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.journals = Objects.requireNonNull(journals, "journals");
    }

    public Status start(AgentLabel agentLabel, AgentMessage.StartSession command)
            throws IOException, AgentRegistryException, SessionRegistryException,
            JournalStorageException, AgentProtocolException {
        try (var authority = agents.acquireAuthority()) {
            synchronized (this) {
                Objects.requireNonNull(command, "command");
                validateAgent(agentLabel);
                if (sessions.find(command.sessionId()).isPresent() || ledger.hasSession(command.sessionId())
                        || journals.lastEventId(command.sessionId()).isPresent()) {
                    throw new IllegalArgumentException("Session ID already exists");
                }
                FileSystemCommandLedger.Entry entry = ledger.reserve(
                        agentLabel, command.commandId(), command.sessionId(), ignored -> command);
                sessions.reserveStart(agentLabel, command.sessionId());
                return deliver(entry);
            }
        }
    }

    public Status input(
            AgentLabel agentLabel, CommandId commandId, SessionId sessionId, UUID inputId, ProtocolBytes bytes)
            throws IOException, AgentRegistryException, SessionRegistryException,
            JournalStorageException, AgentProtocolException {
        try (var authority = agents.acquireAuthority()) {
            synchronized (this) {
                return established(agentLabel, commandId, sessionId,
                        sequence -> new AgentMessage.Input(commandId, sessionId, inputId, bytes, sequence));
            }
        }
    }

    public Status resize(
            AgentLabel agentLabel, CommandId commandId, SessionId sessionId, int columns, int rows)
            throws IOException, AgentRegistryException, SessionRegistryException,
            JournalStorageException, AgentProtocolException {
        try (var authority = agents.acquireAuthority()) {
            synchronized (this) {
                return established(agentLabel, commandId, sessionId,
                        sequence -> new AgentMessage.Resize(commandId, sessionId, columns, rows, sequence));
            }
        }
    }

    public Status signal(
            AgentLabel agentLabel, CommandId commandId, SessionId sessionId,
            AgentMessage.SignalKind signal, int platformCode)
            throws IOException, AgentRegistryException, SessionRegistryException,
            JournalStorageException, AgentProtocolException {
        try (var authority = agents.acquireAuthority()) {
            synchronized (this) {
                return established(agentLabel, commandId, sessionId,
                        sequence -> new AgentMessage.Signal(commandId, sessionId, signal, platformCode, sequence));
            }
        }
    }

    public Status terminate(
            AgentLabel agentLabel, CommandId commandId, SessionId sessionId,
            AgentMessage.TerminationMode mode)
            throws IOException, AgentRegistryException, SessionRegistryException,
            JournalStorageException, AgentProtocolException {
        try (var authority = agents.acquireAuthority()) {
            synchronized (this) {
                return established(agentLabel, commandId, sessionId,
                        sequence -> new AgentMessage.Terminate(commandId, sessionId, mode, sequence));
            }
        }
    }

    private Status established(
            AgentLabel agentLabel, CommandId commandId, SessionId sessionId,
            java.util.function.LongFunction<AgentMessage> messageFactory)
            throws IOException, AgentRegistryException, SessionRegistryException,
            JournalStorageException, AgentProtocolException {
        validateAgent(agentLabel);
        validateSession(agentLabel, sessionId);
        FileSystemCommandLedger.Entry entry = ledger.reserve(agentLabel, commandId, sessionId, messageFactory);
        return deliver(entry);
    }

    private void validateSession(AgentLabel agentLabel, SessionId sessionId)
            throws SessionRegistryException, JournalStorageException {
        SessionRecord record = sessions.find(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Session is not registered"));
        if (!record.agentLabel().equals(agentLabel)) {
            throw new IllegalArgumentException("Session belongs to another agent");
        }
        if (record.descriptor().state() != AgentMessage.SessionState.RUNNING) {
            throw new IllegalArgumentException("Session is not running");
        }
        for (SessionEventRecord event : journals.readAfter(sessionId, Optional.empty()).records()) {
            if (event.eventType() == SessionEventType.PROCESS_EXITED
                    || event.eventType() == SessionEventType.SESSION_START_FAILED) {
                throw new IllegalArgumentException("Session has exited");
            }
        }
    }

    private void validateAgent(AgentLabel agentLabel) throws AgentRegistryException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        if (agents.find(agentLabel).isEmpty()) {
            throw new IllegalArgumentException("Agent is not registered");
        }
    }

    public Status redeliver(CommandId commandId)
            throws IOException, JournalStorageException, SessionRegistryException {
        try (var authority = agents.acquireAuthority()) {
            synchronized (this) {
                FileSystemCommandLedger.Entry entry = requireEntry(commandId);
                Status current = status(commandId);
                if (current.phase() == Phase.CONFIRMED) {
                    return current;
                }
                if (entry.message() instanceof AgentMessage.StartSession) {
                    Optional<SessionRecord> existing = sessions.find(entry.sessionId());
                    if (existing.isEmpty()) {
                        sessions.reserveStart(entry.agentLabel(), entry.sessionId());
                    } else if (!existing.orElseThrow().agentLabel().equals(entry.agentLabel())
                            || existing.orElseThrow().descriptor().state() == AgentMessage.SessionState.EXITED
                            || existing.orElseThrow().descriptor().state() == AgentMessage.SessionState.FAILED) {
                        throw new IllegalArgumentException("Session cannot be restarted");
                    }
                } else {
                    validateSession(entry.agentLabel(), entry.sessionId());
                }
                return deliver(entry);
            }
        }
    }

    public synchronized Status status(CommandId commandId)
            throws IOException, JournalStorageException {
        FileSystemCommandLedger.Entry entry = requireEntry(commandId);
        Status confirmed = journalStatus(entry);
        return confirmed != null ? confirmed : transientStatuses.getOrDefault(commandId,
                new Status(commandId, entry.sessionId(), FileSystemCommandLedger.sequence(entry.message()),
                        Phase.UNKNOWN, Optional.empty(), ""));
    }

    private FileSystemCommandLedger.Entry requireEntry(CommandId commandId) throws IOException {
        FileSystemCommandLedger.Entry entry = ledger.find(Objects.requireNonNull(commandId, "commandId"));
        if (entry == null) {
            throw new IllegalArgumentException("Command ID is unknown");
        }
        return entry;
    }

    private Status journalStatus(FileSystemCommandLedger.Entry entry)
            throws JournalStorageException {
        for (SessionEventRecord event : journals.readAfter(entry.sessionId(), Optional.empty()).records()) {
            if (entry.message() instanceof AgentMessage.StartSession) {
                if (event.eventType() == SessionEventType.PROCESS_STARTED) {
                    try {
                        eventCodec.decodeKnownPayload(event);
                        return confirmed(entry, SessionCommandOutcome.SUCCEEDED, "started");
                    } catch (AgentProtocolException malformedStarted) {
                        continue;
                    }
                }
                if (event.eventType() == SessionEventType.SESSION_START_FAILED) {
                    try {
                        SessionEventPayload.SessionStartFailed failed =
                                (SessionEventPayload.SessionStartFailed) eventCodec.decodeKnownPayload(event)
                                        .orElseThrow();
                        if (failed.commandId().equals(entry.commandId())) {
                            return confirmed(entry, SessionCommandOutcome.FAILED, failed.diagnostic());
                        }
                    } catch (AgentProtocolException malformedFailure) {
                        continue;
                    }
                }
                continue;
            }
            if (event.eventType() != SessionEventType.COMMAND_RESULT) {
                continue;
            }
            SessionEventPayload payload;
            try {
                payload = eventCodec.decodeKnownPayload(event).orElseThrow();
            } catch (AgentProtocolException malformedResult) {
                continue;
            }
            SessionEventPayload.CommandResult result = (SessionEventPayload.CommandResult) payload;
            if (result.source() == SessionCommandSource.SERVER
                    && result.operationSequence() == FileSystemCommandLedger.sequence(entry.message())
                    && result.sourceEnvelope().equals(ProtocolBytes.copyOf(entry.encoded()))) {
                return confirmed(entry, result.outcome(), result.detail());
            }
        }
        return null;
    }

    private Status confirmed(
            FileSystemCommandLedger.Entry entry, SessionCommandOutcome outcome, String detail) {
        return new Status(entry.commandId(), entry.sessionId(),
                FileSystemCommandLedger.sequence(entry.message()), Phase.CONFIRMED,
                Optional.of(outcome), detail);
    }

    private Status deliver(FileSystemCommandLedger.Entry entry) {
        Status pending = new Status(entry.commandId(), entry.sessionId(),
                FileSystemCommandLedger.sequence(entry.message()), Phase.UNKNOWN, Optional.empty(), "");
        transientStatuses.put(entry.commandId(), pending);
        Optional<AuthenticatedAgentConnections.Delivery> sending;
        try {
            sending = connections.send(entry.agentLabel(), entry.message());
        } catch (RuntimeException failure) {
            transientStatuses.put(entry.commandId(), pending.with(Phase.DELIVERY_FAILED, "send failed"));
            return transientStatuses.get(entry.commandId());
        }
        if (sending.isEmpty()) {
            transientStatuses.put(entry.commandId(), pending.with(Phase.DELIVERY_FAILED, "agent offline"));
            return transientStatuses.get(entry.commandId());
        }
        sending.orElseThrow().completion().whenComplete((ignored, failure) -> {
            try (var authority = sending.orElseThrow().context().acquireAuthority()) {
                transientStatuses.computeIfPresent(entry.commandId(), (id, current) ->
                        current == pending ? current.with(
                                failure == null ? Phase.SENT : Phase.DELIVERY_FAILED,
                                failure == null ? "" : "send failed") : current);
            } catch (IllegalStateException rejected) {
                // A superseded connection cannot change delivery status.
            }
        });
        return transientStatuses.get(entry.commandId());
    }

    public AgentControlHandler.Session controlSession(AgentLabel agentLabel) {
        return new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
                if (message instanceof AgentMessage.CommandResult result) {
                    transientResult(agentLabel, result);
                }
            }

            @Override
            public void onClosed(Throwable failure) {
            }
        };
    }

    private void transientResult(AgentLabel agentLabel, AgentMessage.CommandResult result) {
        FileSystemCommandLedger.Entry entry;
        try {
            entry = ledger.find(result.commandId());
        } catch (IOException failure) {
            return;
        }
        if (entry == null || !entry.agentLabel().equals(agentLabel)
                || !result.sessionId().equals(Optional.of(entry.sessionId()))) {
            return;
        }
        Phase phase = result.outcome() == AgentMessage.CommandOutcome.FAILED
                || result.outcome() == AgentMessage.CommandOutcome.REJECTED
                ? Phase.DELIVERY_FAILED : Phase.SENT;
        transientStatuses.put(result.commandId(), new Status(result.commandId(), entry.sessionId(),
                FileSystemCommandLedger.sequence(entry.message()), phase, Optional.empty(), result.detail()));
    }

    @Override
    public void close() throws IOException {
        ledger.close();
    }

    public enum Phase {
        UNKNOWN, SENT, DELIVERY_FAILED, CONFIRMED
    }

    public record Status(CommandId commandId, SessionId sessionId, long operationSequence,
                         Phase phase, Optional<SessionCommandOutcome> outcome, String detail) {
        public Status {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(phase, "phase");
            outcome = Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detail, "detail");
        }

        private Status with(Phase newPhase, String newDetail) {
            return new Status(commandId, sessionId, operationSequence, newPhase, outcome, newDetail);
        }
    }
}
