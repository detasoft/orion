package pro.deta.orion.agentd.journal;

import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JournalReadResult(
        List<SessionEventRecord> records,
        Optional<EventId> firstAvailableEventId,
        Optional<EventId> lastAvailableEventId,
        Optional<JournalCursorGap> gap,
        boolean ignoredIncompleteTail,
        Optional<JournalReadIssue> issue
) {
    public JournalReadResult {
        records = List.copyOf(records);
        firstAvailableEventId = Objects.requireNonNull(firstAvailableEventId, "firstAvailableEventId");
        lastAvailableEventId = Objects.requireNonNull(lastAvailableEventId, "lastAvailableEventId");
        gap = Objects.requireNonNull(gap, "gap");
        issue = Objects.requireNonNull(issue, "issue");
        if (firstAvailableEventId.isPresent() != lastAvailableEventId.isPresent()) {
            throw new IllegalArgumentException("first and last available event IDs must be present together");
        }
        if (firstAvailableEventId.isPresent()
                && firstAvailableEventId.orElseThrow().compareTo(lastAvailableEventId.orElseThrow()) > 0) {
            throw new IllegalArgumentException(
                    "first available event ID must not follow last available event ID");
        }
    }
}
