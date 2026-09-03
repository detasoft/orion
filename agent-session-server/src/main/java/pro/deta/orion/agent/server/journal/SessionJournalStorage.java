package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;

import java.util.List;
import java.util.Optional;

public interface SessionJournalStorage extends AutoCloseable {
    Optional<EventId> firstEventId(SessionId sessionId) throws JournalStorageException;

    Optional<EventId> lastEventId(SessionId sessionId) throws JournalStorageException;

    JournalAppendResult append(SessionId sessionId, List<SessionEventRecord> records)
            throws JournalStorageException;

    JournalReadResult readAfter(SessionId sessionId, Optional<EventId> after)
            throws JournalStorageException;

    @Override
    void close();
}
