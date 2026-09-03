package pro.deta.orion.agentd.journal;

import pro.deta.orion.agent.protocol.EventId;

import java.util.Objects;

public record JournalCursorGap(EventId requestedEventId, EventId firstAvailableEventId) {
    public JournalCursorGap {
        Objects.requireNonNull(requestedEventId, "requestedEventId");
        Objects.requireNonNull(firstAvailableEventId, "firstAvailableEventId");
        if (requestedEventId.compareTo(firstAvailableEventId) >= 0) {
            throw new IllegalArgumentException("requestedEventId must precede firstAvailableEventId");
        }
    }
}
