package pro.deta.orion.agentd.journal;

import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JournalReadPage(
        List<SessionEventRecord> records,
        Optional<EventId> firstAvailableEventId,
        Optional<EventId> lastAvailableEventId,
        Optional<JournalReadPosition> nextPosition,
        JournalReadBoundary boundary,
        Optional<JournalCursorGap> gap,
        Optional<JournalReadIssue> issue
) {
    public JournalReadPage {
        records = List.copyOf(records);
        for (SessionEventRecord record : records) {
            Objects.requireNonNull(record, "records contains null");
        }
        firstAvailableEventId = Objects.requireNonNull(
                firstAvailableEventId,
                "firstAvailableEventId");
        lastAvailableEventId = Objects.requireNonNull(
                lastAvailableEventId,
                "lastAvailableEventId");
        nextPosition = Objects.requireNonNull(nextPosition, "nextPosition");
        boundary = Objects.requireNonNull(boundary, "boundary");
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
        if ((boundary == JournalReadBoundary.GAP) != gap.isPresent()) {
            throw new IllegalArgumentException("gap must be present exactly at a gap boundary");
        }
        if ((boundary == JournalReadBoundary.ISSUE) != issue.isPresent()) {
            throw new IllegalArgumentException("issue must be present exactly at an issue boundary");
        }
        if (!records.isEmpty() && nextPosition.isEmpty()) {
            throw new IllegalArgumentException("a non-empty page must have a next position");
        }
        if (!records.isEmpty() && firstAvailableEventId.isEmpty()) {
            throw new IllegalArgumentException("a non-empty page must have an available event range");
        }
        if ((boundary == JournalReadBoundary.PAGE_LIMIT
                || boundary == JournalReadBoundary.INCOMPLETE_TAIL)
                && nextPosition.isEmpty()) {
            throw new IllegalArgumentException("a resumable boundary must have a next position");
        }
    }
}
