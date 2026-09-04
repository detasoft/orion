package pro.deta.orion.agentd.journal;

import pro.deta.orion.agent.protocol.EventId;

import java.nio.file.Path;
import java.util.Optional;

public interface SessionJournalReader {
    JournalReadResult readAfter(Path sessionDirectory, Optional<EventId> cursor);

    JournalReadPage readPage(
            Path sessionDirectory,
            Optional<EventId> cursor,
            Optional<JournalReadPosition> position,
            JournalReadLimits limits
    );
}
