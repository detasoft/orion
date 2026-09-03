package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JournalReadResult(
        List<SessionEventRecord> records,
        Optional<JournalGap> gap) {
    public JournalReadResult {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        gap = Objects.requireNonNull(gap, "gap");
        for (int index = 1; index < records.size(); index++) {
            if (records.get(index - 1).eventId().compareTo(records.get(index).eventId()) >= 0) {
                throw new IllegalArgumentException("record eventIds must be strictly increasing");
            }
        }
        if (gap.isPresent()
                && (records.isEmpty() || !gap.get().firstAvailable().equals(records.getFirst().eventId()))) {
            throw new IllegalArgumentException("gap firstAvailable must match the first record eventId");
        }
    }
}
