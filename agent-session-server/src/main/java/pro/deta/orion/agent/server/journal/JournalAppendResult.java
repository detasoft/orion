package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JournalAppendResult(
        Optional<EventId> durableThrough,
        List<SessionEventRecord> newlyStored) {
    public JournalAppendResult {
        durableThrough = Objects.requireNonNull(durableThrough, "durableThrough");
        newlyStored = List.copyOf(Objects.requireNonNull(newlyStored, "newlyStored"));
        for (int index = 1; index < newlyStored.size(); index++) {
            EventId previous = newlyStored.get(index - 1).eventId();
            EventId current = newlyStored.get(index).eventId();
            if (previous.compareTo(current) >= 0) {
                throw new IllegalArgumentException("newlyStored eventIds must be strictly increasing");
            }
        }
        if (!newlyStored.isEmpty()
                && (durableThrough.isEmpty()
                || !durableThrough.get().equals(newlyStored.getLast().eventId()))) {
            throw new IllegalArgumentException("durableThrough must match the final newlyStored eventId");
        }
    }
}
