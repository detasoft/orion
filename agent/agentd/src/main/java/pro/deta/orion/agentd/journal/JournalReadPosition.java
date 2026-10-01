package pro.deta.orion.agentd.journal;

import pro.deta.orion.agent.protocol.EventId;

import java.util.Objects;
import java.util.Optional;

public final class JournalReadPosition {
    private final Optional<EventId> lastEventId;
    private final long segmentNumber;
    private final boolean compressed;
    private final long offset;
    private final Object fileKey;
    private final long knownSize;
    private final long oldestSegmentNumber;
    private final boolean oldestCompressed;
    private final Object oldestFileKey;
    private final long oldestKnownSize;
    private final Optional<EventId> firstAvailableEventId;
    private final Optional<EventId> previousPhysicalEventId;

    JournalReadPosition(
            Optional<EventId> lastEventId,
            long segmentNumber,
            boolean compressed,
            long offset,
            Object fileKey,
            long knownSize,
            long oldestSegmentNumber,
            boolean oldestCompressed,
            Object oldestFileKey,
            long oldestKnownSize,
            Optional<EventId> firstAvailableEventId,
            Optional<EventId> previousPhysicalEventId
    ) {
        this.lastEventId = Objects.requireNonNull(lastEventId, "lastEventId");
        this.segmentNumber = segmentNumber;
        this.compressed = compressed;
        this.offset = offset;
        this.fileKey = fileKey;
        this.knownSize = knownSize;
        this.oldestSegmentNumber = oldestSegmentNumber;
        this.oldestCompressed = oldestCompressed;
        this.oldestFileKey = oldestFileKey;
        this.oldestKnownSize = oldestKnownSize;
        this.firstAvailableEventId = Objects.requireNonNull(
                firstAvailableEventId,
                "firstAvailableEventId");
        this.previousPhysicalEventId = Objects.requireNonNull(
                previousPhysicalEventId,
                "previousPhysicalEventId");
        if (segmentNumber < 1 || oldestSegmentNumber < 1) {
            throw new IllegalArgumentException("segment numbers must be positive");
        }
        if (offset < 0 || knownSize < 0 || oldestKnownSize < 0) {
            throw new IllegalArgumentException("offset and known sizes must not be negative");
        }
    }

    public Optional<EventId> lastEventId() {
        return lastEventId;
    }

    long segmentNumber() {
        return segmentNumber;
    }

    boolean compressed() {
        return compressed;
    }

    long offset() {
        return offset;
    }

    Object fileKey() {
        return fileKey;
    }

    long knownSize() {
        return knownSize;
    }

    long oldestSegmentNumber() {
        return oldestSegmentNumber;
    }

    boolean oldestCompressed() {
        return oldestCompressed;
    }

    Object oldestFileKey() {
        return oldestFileKey;
    }

    long oldestKnownSize() {
        return oldestKnownSize;
    }

    Optional<EventId> firstAvailableEventId() {
        return firstAvailableEventId;
    }

    Optional<EventId> previousPhysicalEventId() {
        return previousPhysicalEventId;
    }
}
