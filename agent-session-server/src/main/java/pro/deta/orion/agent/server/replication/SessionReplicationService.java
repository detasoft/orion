package pro.deta.orion.agent.server.replication;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.journal.JournalAppendResult;
import pro.deta.orion.agent.server.journal.JournalStorageException;
import pro.deta.orion.agent.server.journal.SessionJournalStorage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SessionReplicationService {
    private final SessionJournalStorage storage;

    public SessionReplicationService(SessionJournalStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public AgentMessage.SessionSync open(
            AgentId agentId,
            AgentMessage.SessionOpen open) throws SessionReplicationException {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(open, "open");
        try {
            Optional<EventId> durableThrough = storage.lastEventId(open.sessionId());
            return new AgentMessage.SessionSync(open.sessionId(), durableThrough);
        } catch (JournalStorageException failure) {
            throw new SessionReplicationException(
                    SessionReplicationException.Kind.INTERNAL,
                    "Could not establish session replication cursor",
                    failure);
        }
    }

    public AgentMessage.SessionSync append(
            SessionId sessionId,
            List<SessionEventRecord> records) throws SessionReplicationException {
        Objects.requireNonNull(sessionId, "sessionId");
        List<SessionEventRecord> batch = List.copyOf(Objects.requireNonNull(records, "records"));
        if (batch.isEmpty()) {
            throw new SessionReplicationException(
                    SessionReplicationException.Kind.PROTOCOL,
                    "Replication append batch must not be empty");
        }
        try {
            JournalAppendResult result = Objects.requireNonNull(
                    storage.append(sessionId, batch), "storage append result");
            EventId durableThrough = result.durableThrough().orElseThrow(
                    () -> new IllegalStateException("Non-empty append returned no durable cursor"));
            return new AgentMessage.SessionSync(sessionId, Optional.of(durableThrough));
        } catch (JournalStorageException failure) {
            SessionReplicationException.Kind kind = switch (failure.reason()) {
                case INVALID_APPEND, CONFLICTING_DUPLICATE ->
                        SessionReplicationException.Kind.PROTOCOL;
                case STORED_CORRUPTION, IO_FAILURE, CLOSED ->
                        SessionReplicationException.Kind.INTERNAL;
            };
            throw new SessionReplicationException(
                    kind, "Could not append session replication batch", failure);
        } catch (RuntimeException failure) {
            throw new SessionReplicationException(
                    SessionReplicationException.Kind.INTERNAL,
                    "Storage returned an invalid append result",
                    failure);
        }
    }
}
