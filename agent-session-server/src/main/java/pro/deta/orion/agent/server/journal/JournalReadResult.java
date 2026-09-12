package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.util.List;
import java.util.Objects;

public record JournalReadResult(
        List<SessionEventRecord> records) {
    public JournalReadResult {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        for (int index = 1; index < records.size(); index++) {
            if (records.get(index - 1).eventId().compareTo(records.get(index).eventId()) >= 0) {
                throw new IllegalArgumentException("record eventIds must be strictly increasing");
            }
        }
    }
}
