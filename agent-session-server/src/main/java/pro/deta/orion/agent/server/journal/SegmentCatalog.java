package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.EventId;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class SegmentCatalog {
    enum Representation {
        UNCOMPRESSED,
        COMPRESSED
    }

    record FileIdentity(Object fileKey) {
        FileIdentity {
            Objects.requireNonNull(fileKey, "fileKey");
        }
    }

    record Segment(
            long number,
            Optional<EventId> firstEventId,
            Optional<EventId> lastEventId,
            Representation representation,
            Path physicalPath,
            long completeByteLength,
            FileIdentity identity) {
        Segment {
            if (number < 0) {
                throw new IllegalArgumentException("number must be unsigned");
            }
            firstEventId = Objects.requireNonNull(firstEventId, "firstEventId");
            lastEventId = Objects.requireNonNull(lastEventId, "lastEventId");
            Objects.requireNonNull(representation, "representation");
            Objects.requireNonNull(physicalPath, "physicalPath");
            Objects.requireNonNull(identity, "identity");
            if (firstEventId.isPresent() != lastEventId.isPresent()) {
                throw new IllegalArgumentException("segment event bounds must both be present or absent");
            }
            if (firstEventId.isPresent() && firstEventId.get().compareTo(lastEventId.get()) > 0) {
                throw new IllegalArgumentException("segment event bounds must be ordered");
            }
            if (completeByteLength < 0) {
                throw new IllegalArgumentException("completeByteLength must not be negative");
            }
        }
    }

    record LocatedSegment(int index, Segment segment) {
    }

    private final Path sessionDirectory;
    private final Optional<FileIdentity> sessionDirectoryIdentity;
    private final List<Segment> segments;

    SegmentCatalog(
            Path sessionDirectory,
            Optional<FileIdentity> sessionDirectoryIdentity,
            List<Segment> segments) {
        this.sessionDirectory = Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        this.sessionDirectoryIdentity = Objects.requireNonNull(
                sessionDirectoryIdentity, "sessionDirectoryIdentity");
        this.segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (sessionDirectoryIdentity.isEmpty() && !segments.isEmpty()) {
            throw new IllegalArgumentException("Missing session directories cannot contain segments");
        }
    }

    Path sessionDirectory() {
        return sessionDirectory;
    }

    Optional<FileIdentity> sessionDirectoryIdentity() {
        return sessionDirectoryIdentity;
    }

    List<Segment> segments() {
        return segments;
    }

    Optional<EventId> firstEventId() {
        for (Segment segment : segments) {
            if (segment.firstEventId().isPresent()) {
                return segment.firstEventId();
            }
        }
        return Optional.empty();
    }

    Optional<EventId> lastEventId() {
        for (int index = segments.size() - 1; index >= 0; index--) {
            Segment segment = segments.get(index);
            if (segment.lastEventId().isPresent()) {
                return segment.lastEventId();
            }
        }
        return Optional.empty();
    }

    Optional<LocatedSegment> segmentContaining(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        int lower = 0;
        int upper = segments.size() - 1;
        while (lower <= upper) {
            int index = lower + (upper - lower) / 2;
            Segment segment = segments.get(index);
            if (segment.firstEventId().isEmpty()) {
                upper = index - 1;
                continue;
            }
            if (eventId.compareTo(segment.firstEventId().get()) < 0) {
                upper = index - 1;
            } else if (eventId.compareTo(segment.lastEventId().orElseThrow()) > 0) {
                lower = index + 1;
            } else {
                return Optional.of(new LocatedSegment(index, segment));
            }
        }
        return Optional.empty();
    }
}
