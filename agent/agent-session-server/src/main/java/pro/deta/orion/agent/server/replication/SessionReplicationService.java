package pro.deta.orion.agent.server.replication;

import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry.RegistrationLease;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.agent.server.auth.AuthenticatedConnectionContext;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.agent.server.registry.SessionRegistryException;
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

public final class SessionReplicationService implements AutoCloseable {
    private final SessionJournalStorage storage;
    private final FileSystemSessionRegistry sessions;
    private final LiveEventBroker liveEvents = new LiveEventBroker();

    public SessionReplicationService(SessionJournalStorage storage, FileSystemSessionRegistry sessions) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public LiveEventBroker.Subscription subscribe(SessionId sessionId) {
        return liveEvents.subscribe(sessionId);
    }

    @Override
    public void close() {
        liveEvents.close();
    }

    public AgentMessage.SessionSync open(
            AuthenticatedConnectionContext context,
            AgentMessage.SessionOpen open) throws SessionReplicationException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(open, "open");
        try (var authority = acquire(context)) {
            requireOwner(context, open.sessionId());
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
            AuthenticatedConnectionContext context,
            SessionId sessionId,
            List<SessionEventRecord> records) throws SessionReplicationException {
        Objects.requireNonNull(sessionId, "sessionId");
        List<SessionEventRecord> batch = List.copyOf(Objects.requireNonNull(records, "records"));
        if (batch.isEmpty()) {
            throw new SessionReplicationException(
                    SessionReplicationException.Kind.PROTOCOL,
                    "Replication append batch must not be empty");
        }
        try (var authority = acquire(context)) {
            requireOwner(context, sessionId);
            JournalAppendResult result = Objects.requireNonNull(
                    storage.append(sessionId, batch), "storage append result");
            EventId durableThrough = result.durableThrough().orElseThrow(
                    () -> new IllegalStateException("Non-empty append returned no durable cursor"));
            if (!result.newlyStored().isEmpty()) {
                liveEvents.publish(sessionId);
            }
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
    private static FileSystemAgentRegistry.RegistrationLease acquire(
            AuthenticatedConnectionContext context) throws SessionReplicationException {
        try {
            return context.acquireAuthority();
        } catch (IllegalStateException failure) {
            throw new SessionReplicationException(
                    SessionReplicationException.Kind.PROTOCOL, "Agent connection is no longer authoritative", failure);
        }
    }

    private void requireOwner(AuthenticatedConnectionContext context, SessionId sessionId)
            throws SessionReplicationException {
        try {
            if (sessions.find(sessionId).filter(record -> record.agentLabel().equals(context.agentLabel()))
                    .isEmpty()) {
                throw new SessionReplicationException(
                        SessionReplicationException.Kind.PROTOCOL, "Session does not belong to the agent label");
            }
        } catch (SessionRegistryException failure) {
            throw new SessionReplicationException(
                    SessionReplicationException.Kind.INTERNAL, "Could not read session ownership", failure);
        }
    }

}
