package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.EventId;

import java.util.Objects;
import java.util.Optional;

public record JournalObservation(
        boolean readable,
        Optional<EventId> firstAvailableEventId,
        Optional<EventId> lastAvailableEventId
) {
    public static final JournalObservation READABLE =
            new JournalObservation(true, Optional.empty(), Optional.empty());
    public static final JournalObservation MISSING =
            new JournalObservation(false, Optional.empty(), Optional.empty());

    public JournalObservation {
        firstAvailableEventId = Objects.requireNonNull(firstAvailableEventId, "firstAvailableEventId");
        lastAvailableEventId = Objects.requireNonNull(lastAvailableEventId, "lastAvailableEventId");
        if (firstAvailableEventId.isPresent() != lastAvailableEventId.isPresent()) {
            throw new IllegalArgumentException("journal range must be wholly present or absent");
        }
        if (!readable && firstAvailableEventId.isPresent()) {
            throw new IllegalArgumentException("an unreadable journal cannot have an available range");
        }
        if (firstAvailableEventId.isPresent()
                && firstAvailableEventId.orElseThrow().compareTo(lastAvailableEventId.orElseThrow()) > 0) {
            throw new IllegalArgumentException("journal range is reversed");
        }
    }

    public static JournalObservation readable(EventId first, EventId last) {
        return new JournalObservation(
                true,
                Optional.of(Objects.requireNonNull(first, "first")),
                Optional.of(Objects.requireNonNull(last, "last")));
    }
}
