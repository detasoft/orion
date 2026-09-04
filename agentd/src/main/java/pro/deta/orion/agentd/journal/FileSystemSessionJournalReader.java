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
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FileSystemSessionJournalReader {
    private static final Pattern SEGMENT_NAME = Pattern.compile("([0-9]{8})\\.cbor(\\.zst)?");
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final long MAX_DECOMPRESSED_SEGMENT_BYTES = 512L * 1024 * 1024;
    private final Consumer<Path> beforeSegmentRead;

    public FileSystemSessionJournalReader() {
        this(ignored -> { });
    }

    FileSystemSessionJournalReader(Consumer<Path> beforeSegmentRead) {
        this.beforeSegmentRead = Objects.requireNonNull(beforeSegmentRead, "beforeSegmentRead");
    }

    public JournalReadPage readPage(
            Path sessionDirectory,
            Optional<EventId> cursor,
            Optional<JournalReadPosition> position,
            JournalReadLimits limits
    ) {
        Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(limits, "limits");
        if (position.isPresent() && !position.orElseThrow().lastEventId().equals(cursor)) {
            PageAccumulator mismatch = new PageAccumulator(cursor, limits);
            mismatch.issue = new JournalReadIssue.Position(
                    Optional.empty(),
                    "journal cursor does not match the supplied position");
            return mismatch.result();
        }
        PageAccumulator accumulator = readPageSnapshot(sessionDirectory, cursor, position, limits);
        if (accumulator.staleSnapshot) {
            accumulator = readPageSnapshot(sessionDirectory, cursor, Optional.empty(), limits);
        }
        return accumulator.result();
    }

    private PageAccumulator readPageSnapshot(
            Path sessionDirectory,
            Optional<EventId> cursor,
            Optional<JournalReadPosition> position,
            JournalReadLimits limits
    ) {
        PageAccumulator accumulator = new PageAccumulator(cursor, limits);
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
        if (segments.isEmpty()) {
            return accumulator;
        }
        Segment oldest = segments.getFirst();
        SegmentFile oldestFile;
        try {
            oldestFile = segmentFile(oldest);
        } catch (IOException exception) {
            if (exception instanceof NoSuchFileException) {
                accumulator.discardAsStale(ioIssue(oldest, exception));
            } else {
                accumulator.issue = ioIssue(oldest, exception);
            }
            return accumulator;
        }
        accumulator.setOldest(oldest, oldestFile);
        if (position.isPresent()) {
            JournalReadPosition supplied = position.orElseThrow();
            try {
                Optional<PageResume> resume = resume(segments, oldest, oldestFile, supplied);
                if (resume.isPresent()) {
                    PageResume selected = resume.orElseThrow();
                    beforeSegmentRead.accept(segments.get(selected.segmentIndex()).path());
                    accumulator.resume(supplied);
                    scanPageSegments(
                            segments,
                            selected.segmentIndex(),
                            selected.offset(),
                            Optional.of(selected.expectedFile()),
                            accumulator);
                    return accumulator;
                }
            } catch (IOException exception) {
                if (exception instanceof NoSuchFileException) {
                    accumulator.discardAsStale(new JournalReadIssue.Io(
                            Optional.empty(),
                            detail(exception)));
                } else {
                    accumulator.issue = new JournalReadIssue.Io(Optional.empty(), detail(exception));
                }
                return accumulator;
            }
        }
        readPageFromCursor(segments, accumulator);
        return accumulator;
    }

    private void readPageFromCursor(
            List<Segment> segments,
            PageAccumulator accumulator
    ) {
        Segment oldest = segments.getFirst();
        FirstEvent oldestFirst = readFirstEvent(oldest, segments.size() == 1 && !oldest.compressed());
        if (oldestFirst.issue().isPresent()) {
            recordFirstEventIssue(accumulator, oldestFirst);
            return;
        }
        Optional<EventId> oldestEventId = oldestFirst.eventId();
        if (oldestEventId.isEmpty()) {
            if (segments.size() > 1) {
                accumulator.issue = new JournalReadIssue.Layout(
                        Optional.of(oldest.path()),
                        "closed journal segment must not be empty");
                return;
            }
            scanPageSegments(segments, 0, 0, segments.size(), accumulator);
            return;
        }
        EventId availableFirst = oldestEventId.orElseThrow();
        accumulator.first = availableFirst;
        if (accumulator.cursor.isEmpty()) {
            scanPageSegments(segments, 0, 0, segments.size(), accumulator);
            return;
        }
        EventId requested = accumulator.cursor.orElseThrow();
        if (requested.compareTo(availableFirst) < 0) {
            accumulator.gapFirst = availableFirst;
            return;
        }

        int start = 0;
        EventId previousFirst = availableFirst;
        for (int index = 1; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            boolean active = index + 1 == segments.size() && !segment.compressed();
            FirstEvent firstEvent = readFirstEvent(segment, active);
            if (firstEvent.issue().isPresent()) {
                if (firstEvent.staleSnapshot()) {
                    accumulator.discardAsStale(firstEvent.issue().orElseThrow());
                    return;
                }
                scanPageSegments(segments, start, 0, index, accumulator);
                if (accumulator.issue == null) {
                    accumulator.issue = firstEvent.issue().orElseThrow();
                }
                return;
            }
            Optional<EventId> firstEventId = firstEvent.eventId();
            if (firstEventId.isEmpty() && index + 1 < segments.size()) {
                accumulator.issue = new JournalReadIssue.Layout(
                        Optional.of(segment.path()),
                        "closed journal segment must not be empty");
                return;
            }
            if (firstEventId.isPresent()) {
                EventId currentFirst = firstEventId.orElseThrow();
                if (previousFirst != null && currentFirst.compareTo(previousFirst) <= 0) {
                    accumulator.issue = new JournalReadIssue.EventOrder(
                            Optional.of(segment.path()),
                            "journal segment first event IDs must be strictly increasing");
                    return;
                }
                previousFirst = currentFirst;
                if (currentFirst.compareTo(requested) > 0) {
                    break;
                }
                start = index;
            }
        }
        scanPageSegments(segments, start, 0, segments.size(), accumulator);
    }

    private static void recordFirstEventIssue(PageAccumulator accumulator, FirstEvent firstEvent) {
        JournalReadIssue issue = firstEvent.issue().orElseThrow();
        if (firstEvent.staleSnapshot()) {
            accumulator.discardAsStale(issue);
        } else {
            accumulator.issue = issue;
        }
    }

    private static Optional<PageResume> resume(
            List<Segment> segments,
            Segment oldest,
            SegmentFile oldestFile,
            JournalReadPosition position
    ) throws IOException {
        if (oldest.number() != position.oldestSegmentNumber()
                || oldest.compressed() != position.oldestCompressed()
                || !hasSameFileIdentity(position.oldestFileKey(), oldestFile.fileKey())
                || oldest.compressed() && oldestFile.size() != position.oldestKnownSize()
                || !oldest.compressed() && oldestFile.size() < position.oldestKnownSize()) {
            return Optional.empty();
        }
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment.number() != position.segmentNumber()) {
                continue;
            }
            if (segment.compressed() != position.compressed()) {
                return Optional.empty();
            }
            SegmentFile currentFile = segmentFile(segment);
            SegmentFile positionedFile = new SegmentFile(position.fileKey(), position.knownSize());
            if (!hasCompatibleFile(segment, positionedFile, currentFile, position.offset())) {
                return Optional.empty();
            }
            return Optional.of(new PageResume(index, position.offset(), currentFile));
        }
        return Optional.empty();
    }

    static boolean hasSameFileIdentity(Object expected, Object actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private static boolean hasCompatibleFile(
            Segment segment,
            SegmentFile expected,
            SegmentFile actual,
            long offset
    ) {
        if (!hasSameFileIdentity(expected.fileKey(), actual.fileKey())) {
            return false;
        }
        if (segment.compressed()) {
            return expected.size() == actual.size();
        }
        return actual.size() >= expected.size() && actual.size() >= offset;
    }

    private static void scanPageSegments(
            List<Segment> segments,
            int start,
            long firstOffset,
            Optional<SegmentFile> expectedFirstFile,
            PageAccumulator accumulator
    ) {
        scanPageSegments(
                segments,
                start,
                firstOffset,
                segments.size(),
                expectedFirstFile,
                accumulator);
    }

    private static void scanPageSegments(
            List<Segment> segments,
            int start,
            long firstOffset,
            int end,
            PageAccumulator accumulator
    ) {
        scanPageSegments(segments, start, firstOffset, end, Optional.empty(), accumulator);
    }

    private static void scanPageSegments(
            List<Segment> segments,
            int start,
            long firstOffset,
            int end,
            Optional<SegmentFile> expectedFirstFile,
            PageAccumulator accumulator
    ) {
        for (int index = start; index < end; index++) {
            Segment segment = segments.get(index);
            boolean active = index + 1 == segments.size() && !segment.compressed();
            long offset = index == start ? firstOffset : 0;
            Optional<SegmentFile> expectedFile = index == start
                    ? expectedFirstFile
                    : Optional.empty();
            if (!readPageSegment(segment, active, offset, expectedFile, accumulator)) {
                return;
            }
        }
    }

    private static boolean readPageSegment(
            Segment segment,
            boolean active,
            long startOffset,
            Optional<SegmentFile> expectedFile,
            PageAccumulator accumulator
    ) {
        try {
            SegmentFile beforeOpen = segmentFile(segment);
            if (!validateExpectedFile(segment, beforeOpen, startOffset, expectedFile, accumulator)) {
                return false;
            }
            try (InputStream input = open(segment, startOffset)) {
                SegmentFile afterOpen = segmentFile(segment);
                if (!validateExpectedFile(segment, afterOpen, startOffset, expectedFile, accumulator)) {
                    return false;
                }
                accumulator.beginSegment(segment, afterOpen, startOffset);
                boolean accepted = readPageInput(
                        segment,
                        active,
                        startOffset,
                        afterOpen,
                        input,
                        accumulator);
                SegmentFile afterRead = segmentFile(segment);
                if (!validateExpectedFile(segment, afterRead, startOffset, expectedFile, accumulator)) {
                    return false;
                }
                return accepted;
            }
        } catch (IOException exception) {
            if (exception instanceof NoSuchFileException) {
                accumulator.discardAsStale(ioIssue(segment, exception));
                return false;
            }
            accumulator.issue = ioIssue(segment, exception);
            return false;
        }
    }

    private static boolean validateExpectedFile(
            Segment segment,
            SegmentFile actualFile,
            long offset,
            Optional<SegmentFile> expectedFile,
            PageAccumulator accumulator
    ) {
        if (expectedFile.isEmpty()
                || hasCompatibleFile(segment, expectedFile.orElseThrow(), actualFile, offset)) {
            return true;
        }
        accumulator.discardAsStale(new JournalReadIssue.Io(
                Optional.of(segment.path()),
                "journal segment changed while resuming"));
        return false;
    }

    private static boolean readPageInput(
            Segment segment,
            boolean active,
            long startOffset,
            SegmentFile file,
            InputStream input,
            PageAccumulator accumulator
    ) throws IOException {
        SessionEventDecoder decoder = new SessionEventDecoder(AgentProtocolLimits.journalDefaults());
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long decodedBytes = startOffset;
        long completeOffset = startOffset;
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
            PageAccept accepted = acceptPage(
                    decoded,
                    segment,
                    file,
                    completeOffset,
                    accumulator);
            completeOffset = accepted.completeOffset();
            if (!accepted.accepted()) {
                return false;
            }
        }
        if (active && decoder.pendingBytes() > 0) {
            accumulator.incompleteTail = true;
            return true;
        }
        if (!active && completeOffset == 0 && decoder.pendingBytes() == 0) {
            accumulator.issue = new JournalReadIssue.Layout(
                    Optional.of(segment.path()),
                    "closed journal segment must not be empty");
            return false;
        }
        PageAccept accepted = acceptPage(
                decoder.finish(),
                segment,
                file,
                completeOffset,
                accumulator);
        return accepted.accepted();
    }

    private static PageAccept acceptPage(
            SequenceDecodeResult<SessionEventRecord> result,
            Segment segment,
            SegmentFile file,
            long initialOffset,
            PageAccumulator accumulator
    ) {
        long completeOffset = initialOffset;
        for (SequenceDecodeResult.Outcome<SessionEventRecord> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<SessionEventRecord> decoded) {
                SessionEventRecord record = decoded.value();
                completeOffset += record.encodedRecord().size();
                if (!accumulator.accept(record, segment, file, completeOffset)) {
                    return new PageAccept(completeOffset, false);
                }
            } else if (outcome instanceof SequenceDecodeResult.Rejected<SessionEventRecord> rejected) {
                accumulator.issue = issue(segment.path(), rejected.issue().exception());
                return new PageAccept(completeOffset, false);
            }
        }
        if (result.terminalIssue().isPresent()) {
            SequenceDecodeIssue.Terminal terminal = result.terminalIssue().orElseThrow();
            accumulator.issue = issue(segment.path(), terminal.exception());
            return new PageAccept(completeOffset, false);
        }
        return new PageAccept(completeOffset, true);
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

    private FirstEvent readFirstEvent(Segment segment, boolean active) {
        beforeSegmentRead.accept(segment.path());
        SessionEventDecoder decoder = new SessionEventDecoder(AgentProtocolLimits.journalDefaults());
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

    private static InputStream open(Segment segment, long offset) throws IOException {
        if (!segment.compressed()) {
            FileChannel channel = FileChannel.open(segment.path(), StandardOpenOption.READ);
            try {
                channel.position(offset);
                return Channels.newInputStream(channel);
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        }
        InputStream input = open(segment);
        try {
            skipDecoded(input, offset);
            return input;
        } catch (IOException | RuntimeException exception) {
            input.close();
            throw exception;
        }
    }

    private static void skipDecoded(InputStream input, long offset) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long remaining = offset;
        while (remaining > 0) {
            int length = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (length < 0) {
                throw new IOException("compressed journal position exceeds decoded segment length");
            }
            if (length > 0) {
                remaining -= length;
            }
        }
    }

    private static SegmentFile segmentFile(Segment segment) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                segment.path(),
                BasicFileAttributes.class);
        return new SegmentFile(attributes.fileKey(), attributes.size());
    }

    private static JournalReadIssue ioIssue(Segment segment, IOException exception) {
        if (exception instanceof ZstdIOException) {
            return new JournalReadIssue.Decompression(Optional.of(segment.path()), detail(exception));
        }
        return new JournalReadIssue.Io(Optional.of(segment.path()), detail(exception));
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

    private record SegmentFile(Object fileKey, long size) {
    }

    private record PageResume(int segmentIndex, long offset, SegmentFile expectedFile) {
    }

    private record PageAccept(long completeOffset, boolean accepted) {
    }

    private static final class SegmentCandidates {
        private Path raw;
        private Path compressed;
    }

    private static final class PageAccumulator {
        private final Optional<EventId> cursor;
        private final JournalReadLimits limits;
        private final List<SessionEventRecord> records = new ArrayList<>();
        private long encodedBytes;
        private EventId first;
        private EventId orderLast;
        private EventId gapFirst;
        private JournalReadIssue issue;
        private boolean incompleteTail;
        private boolean pageLimit;
        private boolean staleSnapshot;
        private Segment oldest;
        private SegmentFile oldestFile;
        private Segment checkpointSegment;
        private SegmentFile checkpointFile;
        private long checkpointOffset;
        private Optional<EventId> checkpointLogicalEventId;
        private Optional<EventId> checkpointPhysicalEventId;
        private boolean firstRecordInSegment;

        private PageAccumulator(Optional<EventId> cursor, JournalReadLimits limits) {
            this.cursor = cursor;
            this.limits = limits;
            this.checkpointLogicalEventId = cursor;
            this.checkpointPhysicalEventId = Optional.empty();
        }

        private void setOldest(Segment segment, SegmentFile file) {
            oldest = segment;
            oldestFile = file;
        }

        private void resume(JournalReadPosition position) {
            first = position.firstAvailableEventId().orElse(null);
            orderLast = position.previousPhysicalEventId().orElse(null);
            checkpointLogicalEventId = position.lastEventId();
            checkpointPhysicalEventId = position.previousPhysicalEventId();
        }

        private void beginSegment(Segment segment, SegmentFile file, long offset) {
            checkpointSegment = segment;
            checkpointFile = file;
            checkpointOffset = offset;
            firstRecordInSegment = offset == 0;
        }

        private boolean accept(
                SessionEventRecord record,
                Segment segment,
                SegmentFile file,
                long completeOffset
        ) {
            EventId eventId = record.eventId();
            if (eventId.value() == 0 || orderLast != null && eventId.compareTo(orderLast) <= 0) {
                if (firstRecordInSegment && orderLast != null) {
                    records.clear();
                    encodedBytes = 0;
                }
                issue = new JournalReadIssue.EventOrder(
                        Optional.of(segment.path()),
                        "journal event IDs must be nonzero and strictly increasing");
                return false;
            }
            firstRecordInSegment = false;
            if (cursor.isPresent() && eventId.compareTo(cursor.orElseThrow()) <= 0) {
                orderLast = eventId;
                if (first == null) {
                    first = eventId;
                }
                checkpoint(segment, file, completeOffset, cursor, eventId);
                return true;
            }
            int recordBytes = record.encodedRecord().size();
            if (records.size() < limits.maxRecords()
                    && recordBytes <= limits.maxEncodedBytes() - encodedBytes) {
                orderLast = eventId;
                if (first == null) {
                    first = eventId;
                }
                records.add(record);
                encodedBytes += recordBytes;
                checkpoint(
                        segment,
                        file,
                        completeOffset,
                        Optional.of(eventId),
                        eventId);
                if (records.size() == limits.maxRecords()
                        || encodedBytes == limits.maxEncodedBytes()) {
                    pageLimit = true;
                    return false;
                }
                return true;
            }
            pageLimit = true;
            return false;
        }

        private void checkpoint(
                Segment segment,
                SegmentFile file,
                long offset,
                Optional<EventId> logicalEventId,
                EventId physicalEventId
        ) {
            checkpointSegment = segment;
            checkpointFile = file;
            checkpointOffset = offset;
            checkpointLogicalEventId = logicalEventId;
            checkpointPhysicalEventId = Optional.of(physicalEventId);
        }

        private void discardAsStale(JournalReadIssue staleIssue) {
            records.clear();
            encodedBytes = 0;
            first = null;
            orderLast = null;
            issue = staleIssue;
            incompleteTail = false;
            pageLimit = false;
            gapFirst = null;
            staleSnapshot = true;
            checkpointSegment = null;
            checkpointFile = null;
            checkpointOffset = 0;
            checkpointLogicalEventId = cursor;
            checkpointPhysicalEventId = Optional.empty();
        }

        private JournalReadPage result() {
            EventId confirmedFirst = orderLast == null ? null : first;
            EventId availableFirst = gapFirst == null ? confirmedFirst : gapFirst;
            Optional<JournalCursorGap> gap = cursor
                    .filter(ignored -> gapFirst != null)
                    .map(requested -> new JournalCursorGap(requested, gapFirst));
            JournalReadBoundary boundary;
            Optional<JournalReadIssue> resultIssue = Optional.empty();
            if (gap.isPresent()) {
                boundary = JournalReadBoundary.GAP;
            } else if (pageLimit) {
                boundary = JournalReadBoundary.PAGE_LIMIT;
            } else if (issue != null) {
                boundary = JournalReadBoundary.ISSUE;
                resultIssue = Optional.of(issue);
            } else if (incompleteTail) {
                boundary = JournalReadBoundary.INCOMPLETE_TAIL;
            } else {
                boundary = JournalReadBoundary.COMPLETE;
            }
            return new JournalReadPage(
                    gap.isPresent() ? List.of() : records,
                    Optional.ofNullable(availableFirst),
                    gap.isPresent() ? Optional.empty() : position(confirmedFirst),
                    boundary,
                    gap,
                    resultIssue);
        }

        private Optional<JournalReadPosition> position(EventId confirmedFirst) {
            if (checkpointSegment == null || checkpointFile == null || oldest == null || oldestFile == null) {
                return Optional.empty();
            }
            long knownSize = checkpointFile.size();
            if (!checkpointSegment.compressed()) {
                knownSize = Math.max(knownSize, checkpointOffset);
            }
            return Optional.of(new JournalReadPosition(
                    checkpointLogicalEventId,
                    checkpointSegment.number(),
                    checkpointSegment.compressed(),
                    checkpointOffset,
                    checkpointFile.fileKey(),
                    knownSize,
                    oldest.number(),
                    oldest.compressed(),
                    oldestFile.fileKey(),
                    oldestFile.size(),
                    Optional.ofNullable(confirmedFirst),
                    checkpointPhysicalEventId));
        }
    }
}
