package pro.deta.orion.agent.protocol;

import java.util.Objects;

public record SessionEventRecord(
        EventId eventId,
        int eventType,
        ProtocolBytes encodedPayload,
        ProtocolBytes encodedRecord,
        int trailingFieldCount
) {
    public SessionEventRecord {
        Objects.requireNonNull(eventId, "eventId");
        eventType = ProtocolValidation.unsignedShort(eventType, "eventType");
        Objects.requireNonNull(encodedPayload, "encodedPayload");
        Objects.requireNonNull(encodedRecord, "encodedRecord");
        if (trailingFieldCount < 0) {
            throw new IllegalArgumentException("trailingFieldCount must not be negative");
        }
    }
}
