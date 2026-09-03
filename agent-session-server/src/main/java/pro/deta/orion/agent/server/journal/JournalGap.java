package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.EventId;

import java.util.Objects;

public record JournalGap(EventId requested, EventId firstAvailable) {
    public JournalGap {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(firstAvailable, "firstAvailable");
        if (requested.compareTo(firstAvailable) >= 0) {
            throw new IllegalArgumentException("requested must be before firstAvailable");
        }
    }
}
