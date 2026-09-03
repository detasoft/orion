package pro.deta.orion.agentd.journal;

import com.github.luben.zstd.ZstdIOException;
import com.github.luben.zstd.ZstdInputStream;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SequenceDecodeIssue;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionEventDecoder;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FileSystemSessionJournalReader implements SessionJournalReader {
    private static final Pattern SEGMENT_NAME = Pattern.compile("([0-9]{8})\\.cbor(\\.zst)?");
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final long MAX_DECOMPRESSED_SEGMENT_BYTES = 512L * 1024 * 1024;

    @Override
    public JournalReadResult readAfter(Path sessionDirectory, Optional<EventId> cursor) {
        Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        Objects.requireNonNull(cursor, "cursor");
        Accumulator accumulator = readSnapshot(sessionDirectory, cursor);
        if (accumulator.staleSnapshot) {
            accumulator = readSnapshot(sessionDirectory, cursor);
        }
        return accumulator.result();
    }

    private static Accumulator readSnapshot(Path sessionDirectory, Optional<EventId> cursor) {
        Accumulator accumulator = new Accumulator(cursor);
        List<Segment> segments;
        try {
            segments = segments(sessionDirectory);
        } catch (IOException exception) {
            accumulator.issue = new JournalReadIssue.Io(Optional.empty(), detail(exception));
            return accumulator;
        }
        Optional<String> layoutIssue = layoutIssue(segments);
        if (layoutIssue.isPresent()) {
            accumulator.issue = new JournalReadIssue.Layout(Optional.empty(), layoutIssue.orElseThrow());
            return accumulator;
        }
        List<Optional<EventId>> firstEventIds = new ArrayList<>();
        EventId previousFirst = null;
        EventId availableFirst = null;
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            boolean active = index + 1 == segments.size() && !segment.compressed();
            FirstEvent firstEvent = readFirstEvent(segment, active);
            if (firstEvent.issue().isPresent()) {
                if (firstEvent.staleSnapshot()) {
                    accumulator.discardAsStale(firstEvent.issue().orElseThrow());
                    return accumulator;
                }
                accumulator.first = availableFirst;
                int start = startSegment(firstEventIds, cursor);
                scanSegments(segments, start, index, accumulator);
                if (accumulator.issue == null) {
                    accumulator.issue = firstEvent.issue().orElseThrow();
                }
                return accumulator;
            }
            Optional<EventId> firstEventId = firstEvent.eventId();
            if (firstEventId.isEmpty() && index + 1 < segments.size()) {
                accumulator.issue = new JournalReadIssue.Layout(
                        Optional.of(segment.path()),
                        "closed journal segment must not be empty");
                return accumulator;
            }
            if (firstEventId.isPresent()) {
                EventId currentFirst = firstEventId.orElseThrow();
                if (previousFirst != null && currentFirst.compareTo(previousFirst) <= 0) {
                    accumulator.issue = new JournalReadIssue.EventOrder(
                            Optional.of(segment.path()),
                            "journal segment first event IDs must be strictly increasing");
                    return accumulator;
                }
                previousFirst = currentFirst;
                if (availableFirst == null) {
                    availableFirst = currentFirst;
                }
            }
            firstEventIds.add(firstEventId);
        }
        accumulator.first = availableFirst;
        int start = startSegment(firstEventIds, cursor);
        scanSegments(segments, start, segments.size(), accumulator);
        return accumulator;
    }

    private static void scanSegments(
            List<Segment> segments,
            int start,
            int end,
            Accumulator accumulator
    ) {
        for (int index = start; index < end; index++) {
            Segment segment = segments.get(index);
            boolean active = index + 1 == segments.size() && !segment.compressed();
            if (!readSegment(segment, active, accumulator)) {
                return;
            }
        }
    }

    private static List<Segment> segments(Path directory) throws IOException {
        Map<Long, SegmentCandidates> candidates = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Matcher matcher = SEGMENT_NAME.matcher(entry.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                long number = Long.parseLong(matcher.group(1));
                if (number == 0) {
                    continue;
                }
                SegmentCandidates candidate = candidates.computeIfAbsent(
                        number,
                        ignored -> new SegmentCandidates());
                if (matcher.group(2) == null) {
                    candidate.raw = entry;
                } else {
                    candidate.compressed = entry;
                }
            }
        }
        List<Segment> segments = new ArrayList<>(candidates.size());
        for (Map.Entry<Long, SegmentCandidates> entry : candidates.entrySet()) {
            SegmentCandidates candidate = entry.getValue();
            boolean compressed = candidate.raw == null;
            Path path = compressed ? candidate.compressed : candidate.raw;
            segments.add(new Segment(entry.getKey(), path, compressed));
        }
        return segments;
    }

    private static Optional<String> layoutIssue(List<Segment> segments) {
        long previous = 0;
        boolean first = true;
        for (Segment segment : segments) {
            long number = segment.number();
            if (!first && previous + 1 != number) {
                return Optional.of("retained journal segment numbers are not contiguous");
            }
            previous = number;
            first = false;
        }
        return Optional.empty();
    }

    private static int startSegment(List<Optional<EventId>> firstEventIds, Optional<EventId> cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        EventId requested = cursor.orElseThrow();
        int start = 0;
        for (int index = 0; index < firstEventIds.size(); index++) {
            Optional<EventId> first = firstEventIds.get(index);
            if (first.isPresent() && first.orElseThrow().compareTo(requested) <= 0) {
                start = index;
            }
        }
        return start;
    }

    private static FirstEvent readFirstEvent(Segment segment, boolean active) {
        SessionEventDecoder decoder = new SessionEventDecoder(AgentProtocolLimits.defaults());
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try (InputStream input = open(segment)) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length == 0) {
                    continue;
                }
                SequenceDecodeResult<SessionEventRecord> decoded = decoder.accept(
                        ByteBuffer.wrap(buffer, 0, length));
                if (!decoded.outcomes().isEmpty()) {
                    SequenceDecodeResult.Outcome<SessionEventRecord> first = decoded.outcomes().getFirst();
                    if (first instanceof SequenceDecodeResult.Decoded<SessionEventRecord> value) {
                        if (value.value().eventId().value() == 0) {
                            return FirstEvent.failed(new JournalReadIssue.EventOrder(
                                    Optional.of(segment.path()),
                                    "journal event IDs must be nonzero"));
                        }
                        return FirstEvent.found(value.value().eventId());
                    }
                    SequenceDecodeResult.Rejected<SessionEventRecord> rejected =
                            (SequenceDecodeResult.Rejected<SessionEventRecord>) first;
                    return FirstEvent.failed(issue(segment.path(), rejected.issue().exception()));
                }
                if (decoded.terminalIssue().isPresent()) {
                    return FirstEvent.failed(issue(
                            segment.path(),
                            decoded.terminalIssue().orElseThrow().exception()));
                }
            }
            if (decoder.pendingBytes() == 0) {
                return FirstEvent.empty();
            }
            if (active) {
                return FirstEvent.empty();
            }
            SequenceDecodeIssue.Terminal terminal = decoder.finish().terminalIssue().orElseThrow();
            return FirstEvent.failed(issue(segment.path(), terminal.exception()));
        } catch (IOException exception) {
            return FirstEvent.failed(ioIssue(segment, exception), exception instanceof NoSuchFileException);
        }
    }

    private static boolean readSegment(Segment segment, boolean active, Accumulator accumulator) {
        SessionEventDecoder decoder = new SessionEventDecoder(AgentProtocolLimits.defaults());
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long decodedBytes = 0;
        try (InputStream input = open(segment)) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length == 0) {
                    continue;
                }
                decodedBytes += length;
                if (segment.compressed() && decodedBytes > MAX_DECOMPRESSED_SEGMENT_BYTES) {
                    accumulator.issue = new JournalReadIssue.Limit(
                            Optional.of(segment.path()),
                            "decompressed journal segment exceeds 512 MiB");
                    return false;
                }
                SequenceDecodeResult<SessionEventRecord> decoded = decoder.accept(
                        ByteBuffer.wrap(buffer, 0, length));
                if (!accept(decoded, segment.path(), accumulator)) {
                    return false;
                }
            }
            if (active && decoder.pendingBytes() > 0) {
                accumulator.ignoredIncompleteTail = true;
                return true;
            }
            return accept(decoder.finish(), segment.path(), accumulator);
        } catch (IOException exception) {
            if (exception instanceof NoSuchFileException) {
                accumulator.discardAsStale(ioIssue(segment, exception));
                return false;
            }
            accumulator.issue = ioIssue(segment, exception);
            return false;
        }
    }

    private static InputStream open(Segment segment) throws IOException {
        InputStream file = Files.newInputStream(segment.path());
        if (!segment.compressed()) {
            return file;
        }
        try {
            return new ZstdInputStream(file);
        } catch (IOException exception) {
            file.close();
            throw exception;
        }
    }

    private static JournalReadIssue ioIssue(Segment segment, IOException exception) {
        if (exception instanceof ZstdIOException) {
            return new JournalReadIssue.Decompression(Optional.of(segment.path()), detail(exception));
        }
        return new JournalReadIssue.Io(Optional.of(segment.path()), detail(exception));
    }

    private static boolean accept(
            SequenceDecodeResult<SessionEventRecord> result,
            Path segment,
            Accumulator accumulator
    ) {
        for (SequenceDecodeResult.Outcome<SessionEventRecord> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<SessionEventRecord> decoded) {
                if (!accumulator.accept(decoded.value(), segment)) {
                    return false;
                }
            } else if (outcome instanceof SequenceDecodeResult.Rejected<SessionEventRecord> rejected) {
                accumulator.issue = issue(segment, rejected.issue().exception());
                return false;
            }
        }
        if (result.terminalIssue().isPresent()) {
            SequenceDecodeIssue.Terminal terminal = result.terminalIssue().orElseThrow();
            accumulator.issue = issue(segment, terminal.exception());
            return false;
        }
        return true;
    }

    private static JournalReadIssue issue(Path segment, AgentProtocolException exception) {
        if (exception.reason() == AgentProtocolException.Reason.LIMIT_EXCEEDED) {
            return new JournalReadIssue.Limit(Optional.of(segment), detail(exception));
        }
        if (exception.reason() == AgentProtocolException.Reason.MALFORMED_CBOR) {
            return new JournalReadIssue.Cbor(Optional.of(segment), detail(exception));
        }
        return new JournalReadIssue.Record(Optional.of(segment), detail(exception));
    }

    private static String detail(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 4096 ? message : message.substring(0, 4096);
    }

    private record FirstEvent(
            Optional<EventId> eventId,
            Optional<JournalReadIssue> issue,
            boolean staleSnapshot
    ) {
        private static FirstEvent found(EventId eventId) {
            return new FirstEvent(Optional.of(eventId), Optional.empty(), false);
        }

        private static FirstEvent empty() {
            return new FirstEvent(Optional.empty(), Optional.empty(), false);
        }

        private static FirstEvent failed(JournalReadIssue issue) {
            return failed(issue, false);
        }

        private static FirstEvent failed(JournalReadIssue issue, boolean staleSnapshot) {
            return new FirstEvent(Optional.empty(), Optional.of(issue), staleSnapshot);
        }
    }

    private record Segment(long number, Path path, boolean compressed) {
    }

    private static final class SegmentCandidates {
        private Path raw;
        private Path compressed;
    }

    private static final class Accumulator {
        private final Optional<EventId> cursor;
        private final List<SessionEventRecord> records = new ArrayList<>();
        private EventId first;
        private EventId last;
        private JournalReadIssue issue;
        private boolean ignoredIncompleteTail;
        private boolean staleSnapshot;

        private Accumulator(Optional<EventId> cursor) {
            this.cursor = cursor;
        }

        private boolean accept(SessionEventRecord record, Path segment) {
            EventId eventId = record.eventId();
            if (eventId.value() == 0 || last != null && eventId.compareTo(last) <= 0) {
                issue = new JournalReadIssue.EventOrder(
                        Optional.of(segment),
                        "journal event IDs must be nonzero and strictly increasing");
                return false;
            }
            if (first == null) {
                first = eventId;
            }
            last = eventId;
            if (cursor.isEmpty() || eventId.compareTo(cursor.orElseThrow()) > 0) {
                records.add(record);
            }
            return true;
        }

        private void discardAsStale(JournalReadIssue staleIssue) {
            records.clear();
            first = null;
            last = null;
            ignoredIncompleteTail = false;
            issue = staleIssue;
            staleSnapshot = true;
        }

        private JournalReadResult result() {
            EventId confirmedFirst = last == null ? null : first;
            Optional<JournalCursorGap> gap = cursor
                    .filter(requested -> confirmedFirst != null && requested.compareTo(confirmedFirst) < 0)
                    .map(requested -> new JournalCursorGap(requested, confirmedFirst));
            return new JournalReadResult(
                    records,
                    Optional.ofNullable(confirmedFirst),
                    Optional.ofNullable(last),
                    gap,
                    ignoredIncompleteTail,
                    Optional.ofNullable(issue));
        }
    }
}
