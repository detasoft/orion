package pro.deta.orion.agent.server.journal;

import com.github.luben.zstd.ZstdIOException;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionEventDecoder;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.IO_FAILURE;
import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.STORED_CORRUPTION;

/**
 * @AiRule Decoder-detectable malformed complete CBOR items are {@code STORED_CORRUPTION}. Terminal
 * decoder-pending bytes in only the highest active {@code .cbor} are treated as an interrupted append and
 * truncated. Storage assumes exclusive ownership and reliable durable media. Without forbidden persisted
 * boundary metadata, it cannot detect out-of-band or media corruption that changes a previously complete
 * terminal item into a syntactically valid incomplete prefix.
 */
final class SegmentReader {
    private static final int INITIAL_READ_BYTES = 64 * 1024;
    private static final Pattern SEGMENT_NAME = Pattern.compile("^(\\d{8})\\.(cbor|cbor\\.zst)$");
    private static final Pattern TEMPORARY_NAME = Pattern.compile("^\\d{8}\\.cbor\\.zst\\.tmp$");

    private final AgentProtocolLimits limits;
    private final DurableFileOperations operations;
    private final long maxLogicalSegmentBytes;
    private final long maxZstdWindowBytes;

    SegmentReader(AgentProtocolLimits limits) {
        this(new JournalStorageConfig(limits), new DurableFileOperations());
    }

    SegmentReader(AgentProtocolLimits limits, DurableFileOperations operations) {
        this(new JournalStorageConfig(limits), operations);
    }

    SegmentReader(JournalStorageConfig config) {
        this(config, new DurableFileOperations());
    }

    SegmentReader(JournalStorageConfig config, DurableFileOperations operations) {
        this.limits = Objects.requireNonNull(config, "config").protocolLimits();
        this.operations = Objects.requireNonNull(operations, "operations");
        maxLogicalSegmentBytes = config.maxLogicalSegmentBytes();
        maxZstdWindowBytes = config.maxZstdWindowBytes();
    }

    SegmentCatalog rebuild(Path sessionDirectory) throws JournalStorageException {
        return recover(sessionDirectory).catalog();
    }

    RecoveryScan recover(Path sessionDirectory) throws JournalStorageException {
        SessionDirectoryIdentity identity = inspectSessionDirectory(sessionDirectory, true);
        if (identity == null) {
            return new RecoveryScan(
                    new SegmentCatalog(sessionDirectory, Optional.empty(), List.of()),
                    List.of(),
                    List.of());
        }

        Discovery discovery = discover(sessionDirectory, identity);
        List<DiscoveredSegment> discovered = discovery.segments();
        discovered.sort((left, right) -> Long.compareUnsigned(left.number(), right.number()));
        List<DiscoveredPair> pairs = pairRepresentations(discovered);
        validateNumbers(pairs);

        List<SegmentCatalog.Segment> segments = new ArrayList<>(pairs.size());
        List<DurableFileOperations.RecoveryContent> contents = new ArrayList<>(pairs.size());
        List<DurableFileOperations.CleanupToken> cleanup = new ArrayList<>(
                discovery.temporaryFiles());
        EventId previousEventId = null;
        for (int index = 0; index < pairs.size(); index++) {
            DiscoveredPair pair = pairs.get(index);
            boolean finalActive = index == pairs.size() - 1 && pair.compressed().isEmpty();
            ScannedSegment scanned = scanPair(pair, identity, finalActive);
            DiscoveredSegment item = scanned.selected();
            ReadSegment read = scanned.read();
            cleanup.addAll(scanned.cleanupTokens());
            if (previousEventId != null
                    && read.firstEventId().isPresent()
                    && previousEventId.compareTo(read.firstEventId().get()) >= 0) {
                throw corruption("Stored event IDs are not strictly increasing");
            }
            if (read.lastEventId().isPresent()) {
                previousEventId = read.lastEventId().get();
            }
            SegmentCatalog.Segment segment = new SegmentCatalog.Segment(
                    item.number(),
                    read.firstEventId(),
                    read.lastEventId(),
                    item.representation(),
                    item.path(),
                    read.completeByteLength(),
                    item.identity());
            segments.add(segment);
            contents.add(recoveryContent(segment, read));
        }
        return new RecoveryScan(
                new SegmentCatalog(
                        sessionDirectory,
                        Optional.of(new SegmentCatalog.FileIdentity(identity.fileKey())),
                        segments),
                contents,
                cleanup);
    }

    private ScannedSegment scanPair(
            DiscoveredPair pair,
            SessionDirectoryIdentity identity,
            boolean finalActive) throws JournalStorageException {
        if (pair.uncompressed().isEmpty()) {
            DiscoveredSegment compressed = pair.compressed().orElseThrow();
            ReadSegment read = decodeDiscovered(compressed, identity);
            validateCompletion(read, false);
            return new ScannedSegment(compressed, read, List.of());
        }

        DiscoveredSegment uncompressed = pair.uncompressed().orElseThrow();
        ReadSegment source = decodeDiscovered(uncompressed, identity);
        validateCompletion(source, pair.compressed().isEmpty() && finalActive);
        if (pair.compressed().isEmpty()) {
            return new ScannedSegment(uncompressed, source, List.of());
        }

        DiscoveredSegment compressed = pair.compressed().orElseThrow();
        ReadSegment replacement;
        try {
            replacement = decodeDiscovered(compressed, identity);
            validateCompletion(replacement, false);
        } catch (JournalStorageException e) {
            if (e.reason() != STORED_CORRUPTION) {
                throw e;
            }
            return new ScannedSegment(uncompressed, source, List.of(compressed.cleanupToken()));
        }
        if (!equivalent(source, replacement)) {
            throw corruption("Journal segment representations have divergent logical contents");
        }
        return new ScannedSegment(compressed, replacement, List.of(uncompressed.cleanupToken()));
    }

    private ReadSegment decodeDiscovered(
            DiscoveredSegment segment,
            SessionDirectoryIdentity identity) throws JournalStorageException {
        return decode(
                segment.path(),
                identity,
                segment.identity(),
                segment.representation(),
                Optional.empty(),
                Optional.empty(),
                false);
    }

    private DurableFileOperations.RecoveryContent recoveryContent(
            SegmentCatalog.Segment segment,
            ReadSegment read) throws JournalStorageException {
        try {
            if (segment.representation() == SegmentCatalog.Representation.UNCOMPRESSED) {
                return operations.captureRecoveryContent(segment);
            }
            DurableFileOperations.PhysicalFingerprint physical = operations.capturePhysicalFingerprint(
                    segment.physicalPath(), segment.identity());
            DurableFileOperations.SegmentContent committed =
                    new DurableFileOperations.SegmentContent(
                            segment.physicalPath(),
                            segment.identity(),
                            read.completeByteLength(),
                            read.digest(),
                            physical);
            return new DurableFileOperations.RecoveryContent(
                    committed, SegmentCatalog.Representation.COMPRESSED);
        } catch (IOException e) {
            throw new JournalStorageException(
                    IO_FAILURE, "Could not capture journal segment recovery contents", e);
        }
    }

    private static boolean equivalent(ReadSegment left, ReadSegment right) {
        return left.incompleteBytes() == 0
                && right.incompleteBytes() == 0
                && left.completeByteLength() == right.completeByteLength()
                && left.firstEventId().equals(right.firstEventId())
                && left.lastEventId().equals(right.lastEventId())
                && MessageDigest.isEqual(left.digest(), right.digest());
    }

    private static void validateCompletion(ReadSegment read, boolean finalActive)
            throws JournalStorageException {
        if (read.incompleteBytes() > 0 && !finalActive) {
            throw corruption("A non-final journal segment has an incomplete record");
        }
        if (read.firstEventId().isEmpty() && !finalActive) {
            throw corruption("A non-final journal segment is empty");
        }
    }

    List<SessionEventRecord> readRecords(SegmentCatalog snapshot) throws JournalStorageException {
        return readRecords(snapshot, Optional.empty());
    }

    List<SessionEventRecord> readRecords(
            SegmentCatalog snapshot,
            List<DurableFileOperations.SegmentContent> expectedContents)
            throws JournalStorageException {
        return readRecords(snapshot, Optional.of(List.copyOf(expectedContents)));
    }

    private List<SessionEventRecord> readRecords(
            SegmentCatalog snapshot,
            Optional<List<DurableFileOperations.SegmentContent>> expectedContents)
            throws JournalStorageException {
        if (snapshot.segments().isEmpty()) {
            return List.of();
        }
        validateExpectedContents(snapshot, expectedContents);
        List<SessionEventRecord> records = new ArrayList<>();
        EventId previousEventId = null;
        for (int index = 0; index < snapshot.segments().size(); index++) {
            ReadSegment read = readCommittedSegment(snapshot, index, expectedContents);
            for (SessionEventRecord record : read.records()) {
                if (previousEventId != null && previousEventId.compareTo(record.eventId()) >= 0) {
                    throw corruption("Stored event IDs are not strictly increasing");
                }
                records.add(record);
                previousEventId = record.eventId();
            }
        }
        return List.copyOf(records);
    }

    List<Optional<SessionEventRecord>> locateRecords(
            SegmentCatalog snapshot,
            List<DurableFileOperations.SegmentContent> expectedContents,
            List<EventId> eventIds) throws JournalStorageException {
        Objects.requireNonNull(eventIds, "eventIds");
        Optional<List<DurableFileOperations.SegmentContent>> contents =
                Optional.of(List.copyOf(expectedContents));
        validateExpectedContents(snapshot, contents);
        List<Optional<SessionEventRecord>> located = new ArrayList<>(
                Collections.nCopies(eventIds.size(), Optional.empty()));
        Map<Integer, List<RequestedRecord>> requestsBySegment = new TreeMap<>();
        for (int requestIndex = 0; requestIndex < eventIds.size(); requestIndex++) {
            EventId eventId = Objects.requireNonNull(eventIds.get(requestIndex), "eventId");
            Optional<SegmentCatalog.LocatedSegment> candidate = snapshot.segmentContaining(eventId);
            if (candidate.isPresent()) {
                requestsBySegment.computeIfAbsent(candidate.get().index(), ignored -> new ArrayList<>())
                        .add(new RequestedRecord(requestIndex, eventId));
            }
        }

        for (Map.Entry<Integer, List<RequestedRecord>> entry : requestsBySegment.entrySet()) {
            List<EventId> requestedEventIds = new ArrayList<>(entry.getValue().size());
            for (RequestedRecord request : entry.getValue()) {
                requestedEventIds.add(request.eventId());
            }
            ReadSegment segment = readCommittedSegment(
                    snapshot,
                    entry.getKey(),
                    contents,
                    new DecodedRecords(
                            requestedEventIds,
                            operations::retryLookupRecordRetained));
            int storedIndex = 0;
            for (RequestedRecord request : entry.getValue()) {
                while (storedIndex < segment.records().size()
                        && segment.records().get(storedIndex).eventId().compareTo(request.eventId()) < 0) {
                    storedIndex++;
                }
                if (storedIndex < segment.records().size()
                        && segment.records().get(storedIndex).eventId().equals(request.eventId())) {
                    located.set(request.index(), Optional.of(segment.records().get(storedIndex)));
                }
            }
        }
        return List.copyOf(located);
    }

    private void validateExpectedContents(
            SegmentCatalog snapshot,
            Optional<List<DurableFileOperations.SegmentContent>> expectedContents)
            throws JournalStorageException {
        if (expectedContents.isPresent()
                && expectedContents.get().size() != snapshot.segments().size()) {
            throw new JournalStorageException(
                    IO_FAILURE, "The journal content fingerprints do not match the snapshot");
        }
    }

    private ReadSegment readCommittedSegment(
            SegmentCatalog snapshot,
            int index,
            Optional<List<DurableFileOperations.SegmentContent>> expectedContents)
            throws JournalStorageException {
        return readCommittedSegment(
                snapshot, index, expectedContents, new DecodedRecords(true));
    }

    private ReadSegment readCommittedSegment(
            SegmentCatalog snapshot,
            int index,
            Optional<List<DurableFileOperations.SegmentContent>> expectedContents,
            DecodedRecords decodedRecords) throws JournalStorageException {
        SegmentCatalog.FileIdentity catalogIdentity = snapshot.sessionDirectoryIdentity()
                .orElseThrow(() -> new JournalStorageException(
                        IO_FAILURE, "The journal snapshot has no session directory identity"));
        SessionDirectoryIdentity identity = new SessionDirectoryIdentity(catalogIdentity.fileKey());
        verifySessionDirectory(snapshot.sessionDirectory(), identity);
        SegmentCatalog.Segment segment = snapshot.segments().get(index);
        ReadSegment read = decode(
                segment.physicalPath(),
                identity,
                segment.identity(),
                segment.representation(),
                Optional.of(segment.completeByteLength()),
                expectedDigest(segment, index, expectedContents),
                decodedRecords);
        if (read.incompleteBytes() != 0
                || read.completeByteLength() != segment.completeByteLength()) {
            throw corruption("A journal segment changed while it was being read");
        }
        if (!read.firstEventId().equals(segment.firstEventId())
                || !read.lastEventId().equals(segment.lastEventId())) {
            throw corruption("A journal segment changed while it was being read");
        }
        return read;
    }

    private Optional<byte[]> expectedDigest(
            SegmentCatalog.Segment segment,
            int index,
            Optional<List<DurableFileOperations.SegmentContent>> expectedContents)
            throws JournalStorageException {
        if (expectedContents.isEmpty()) {
            return Optional.empty();
        }
        DurableFileOperations.SegmentContent expected = expectedContents.get().get(index);
        if (!expected.path().equals(segment.physicalPath())
                || !expected.identity().equals(segment.identity())
                || expected.length() != segment.completeByteLength()) {
            throw new JournalStorageException(
                    IO_FAILURE, "A journal content fingerprint does not match its snapshot segment");
        }
        return Optional.of(expected.digest());
    }

    CompressedSegment validateCompressedReplacement(
            SegmentCatalog snapshot,
            SegmentCatalog.Segment source,
            DurableFileOperations.SegmentContent sourceContent,
            Path replacementPath) throws JournalStorageException {
        SegmentCatalog.FileIdentity directoryIdentity = snapshot.sessionDirectoryIdentity()
                .orElseThrow(() -> new JournalStorageException(
                        IO_FAILURE, "The journal snapshot has no session directory identity"));
        SegmentCatalog.FileIdentity replacementIdentity;
        try {
            replacementIdentity = operations.captureIdentity(replacementPath);
        } catch (IOException e) {
            throw new JournalStorageException(
                    IO_FAILURE, "Could not establish compressed segment identity", e);
        }
        ReadSegment read = decode(
                replacementPath,
                new SessionDirectoryIdentity(directoryIdentity.fileKey()),
                replacementIdentity,
                SegmentCatalog.Representation.COMPRESSED,
                Optional.of(source.completeByteLength()),
                Optional.of(sourceContent.digest()),
                false);
        validateCompletion(read, false);
        if (!read.firstEventId().equals(source.firstEventId())
                || !read.lastEventId().equals(source.lastEventId())) {
            throw corruption("A compressed replacement has different event bounds");
        }
        try {
            DurableFileOperations.PhysicalFingerprint physical = operations.capturePhysicalFingerprint(
                    replacementPath, replacementIdentity);
            SegmentCatalog.Segment replacement = new SegmentCatalog.Segment(
                    source.number(),
                    source.firstEventId(),
                    source.lastEventId(),
                    SegmentCatalog.Representation.COMPRESSED,
                    replacementPath,
                    source.completeByteLength(),
                    replacementIdentity);
            DurableFileOperations.SegmentContent content =
                    new DurableFileOperations.SegmentContent(
                            replacementPath,
                            replacementIdentity,
                            source.completeByteLength(),
                            sourceContent.digest(),
                            physical);
            return new CompressedSegment(replacement, content);
        } catch (IOException e) {
            throw new JournalStorageException(
                    IO_FAILURE, "Could not fingerprint compressed replacement", e);
        }
    }

    void verifyAppendTarget(
            SegmentCatalog snapshot,
            Path segmentPath,
            SegmentCatalog.FileIdentity segmentIdentity) throws JournalStorageException {
        SegmentCatalog.FileIdentity catalogIdentity = snapshot.sessionDirectoryIdentity()
                .orElseThrow(() -> new JournalStorageException(
                        IO_FAILURE, "The journal snapshot has no session directory identity"));
        verifySessionDirectory(
                snapshot.sessionDirectory(), new SessionDirectoryIdentity(catalogIdentity.fileKey()));
        verifySegment(segmentPath, segmentIdentity);
    }

    void verifyLayout(SegmentCatalog snapshot) throws JournalStorageException {
        verifyLayout(snapshot, List.of());
    }

    void verifyLayout(
            SegmentCatalog snapshot,
            List<LayoutTransition> toleratedTransitions)
            throws JournalStorageException {
        SegmentCatalog.FileIdentity catalogIdentity = snapshot.sessionDirectoryIdentity()
                .orElseThrow(() -> new JournalStorageException(
                        IO_FAILURE, "The journal snapshot has no session directory identity"));
        SessionDirectoryIdentity identity = new SessionDirectoryIdentity(catalogIdentity.fileKey());
        verifySessionDirectory(snapshot.sessionDirectory(), identity);
        List<DiscoveredPair> discovered;
        List<DurableFileOperations.CleanupToken> temporaryFiles;
        try {
            Discovery discovery = discover(snapshot.sessionDirectory(), identity);
            discovery.segments().sort(
                    (left, right) -> Long.compareUnsigned(left.number(), right.number()));
            discovered = pairRepresentations(discovery.segments());
            temporaryFiles = discovery.temporaryFiles();
        } catch (JournalStorageException e) {
            if (e.reason() != STORED_CORRUPTION) {
                throw e;
            }
            throw new JournalStorageException(
                    IO_FAILURE, "The session journal entry set changed before publication", e);
        }
        if (discovered.size() != snapshot.segments().size()) {
            throw new JournalStorageException(
                    IO_FAILURE, "The session journal entry set changed before publication");
        }
        for (DurableFileOperations.CleanupToken temporary : temporaryFiles) {
            requireExactTransition(temporary, toleratedTransitions);
        }
        for (int index = 0; index < discovered.size(); index++) {
            DiscoveredPair pair = discovered.get(index);
            SegmentCatalog.Segment expected = snapshot.segments().get(index);
            if (pair.uncompressed().isPresent()
                    && pair.compressed().isPresent()) {
                DurableFileOperations.CleanupToken alternate = alternateToken(pair, expected);
                requireExactTransition(alternate, toleratedTransitions);
            }
            DiscoveredSegment actual = expected.representation()
                    == SegmentCatalog.Representation.UNCOMPRESSED
                    ? pair.uncompressed().orElse(null)
                    : pair.compressed().orElse(null);
            if (actual == null
                    || actual.number() != expected.number()
                    || !actual.path().equals(expected.physicalPath())
                    || !actual.identity().equals(expected.identity())) {
                throw new JournalStorageException(
                        IO_FAILURE, "A session journal entry changed before publication");
            }
        }
        verifyTransitionStates(discovered, temporaryFiles, toleratedTransitions);
        verifySessionDirectory(snapshot.sessionDirectory(), identity);
    }

    private static DurableFileOperations.CleanupToken alternateToken(
            DiscoveredPair pair,
            SegmentCatalog.Segment expected) {
        return expected.representation() == SegmentCatalog.Representation.UNCOMPRESSED
                ? pair.compressed().orElseThrow().cleanupToken()
                : pair.uncompressed().orElseThrow().cleanupToken();
    }

    private static void requireExactTransition(
            DurableFileOperations.CleanupToken actual,
            List<LayoutTransition> toleratedTransitions)
            throws JournalStorageException {
        for (LayoutTransition transition : toleratedTransitions) {
            DurableFileOperations.CleanupToken expected = transition.cleanup();
            if (expected.path().equals(actual.path())) {
                if (transition.state() != TransitionState.ABSENT
                        && expected.identity().equals(actual.identity())) {
                    return;
                }
                throw new JournalStorageException(
                        IO_FAILURE, "A deferred journal cleanup entry changed identity");
            }
        }
        throw new JournalStorageException(
                IO_FAILURE, "An unexpected transitional journal entry appeared");
    }

    private static void verifyTransitionStates(
            List<DiscoveredPair> discovered,
            List<DurableFileOperations.CleanupToken> temporaryFiles,
            List<LayoutTransition> expectedTransitions) throws JournalStorageException {
        List<DurableFileOperations.CleanupToken> actualEntries = new ArrayList<>(temporaryFiles);
        for (DiscoveredPair pair : discovered) {
            pair.uncompressed().ifPresent(segment -> actualEntries.add(segment.cleanupToken()));
            pair.compressed().ifPresent(segment -> actualEntries.add(segment.cleanupToken()));
        }
        for (LayoutTransition transition : expectedTransitions) {
            DurableFileOperations.CleanupToken actual = null;
            for (DurableFileOperations.CleanupToken entry : actualEntries) {
                if (entry.path().equals(transition.cleanup().path())) {
                    actual = entry;
                    break;
                }
            }
            if (transition.state() == TransitionState.ABSENT && actual != null) {
                throw new JournalStorageException(
                        IO_FAILURE, "An absent journal transition unexpectedly appeared");
            }
            if (transition.state() == TransitionState.PRESENT
                    && (actual == null || !transition.cleanup().identity().equals(actual.identity()))) {
                throw new JournalStorageException(
                        IO_FAILURE, "A present journal transition changed or disappeared");
            }
            if (transition.state() == TransitionState.UNLINKING
                    && actual != null
                    && !transition.cleanup().identity().equals(actual.identity())) {
                throw new JournalStorageException(
                        IO_FAILURE, "A journal transition changed while being unlinked");
            }
        }
    }

    private Discovery discover(
            Path sessionDirectory,
            SessionDirectoryIdentity identity) throws JournalStorageException {
        List<DiscoveredSegment> discovered = new ArrayList<>();
        List<DurableFileOperations.CleanupToken> temporaryFiles = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionDirectory)) {
            verifySessionDirectory(sessionDirectory, identity);
            try {
                for (Path entry : entries) {
                    verifySessionDirectory(sessionDirectory, identity);
                    String name = entry.getFileName().toString();
                    Matcher segmentMatch = SEGMENT_NAME.matcher(name);
                    boolean temporary = TEMPORARY_NAME.matcher(name).matches();
                    if (!segmentMatch.matches() && !temporary) {
                        throw corruption("The session journal contains an unrecognized entry");
                    }
                    BasicFileAttributes attributes = inspectEntry(entry);
                    verifySessionDirectory(sessionDirectory, identity);
                    if (!attributes.isRegularFile()) {
                        throw corruption("A session journal entry is not a regular file");
                    }
                    if (attributes.fileKey() == null) {
                        throw new JournalStorageException(
                                IO_FAILURE, "Could not establish journal segment identity");
                    }
                    SegmentCatalog.FileIdentity fileIdentity =
                            new SegmentCatalog.FileIdentity(attributes.fileKey());
                    if (temporary) {
                        temporaryFiles.add(
                                new DurableFileOperations.CleanupToken(entry, fileIdentity));
                        continue;
                    }
                    long number = Long.parseUnsignedLong(segmentMatch.group(1));
                    SegmentCatalog.Representation representation = segmentMatch.group(2).equals("cbor")
                            ? SegmentCatalog.Representation.UNCOMPRESSED
                            : SegmentCatalog.Representation.COMPRESSED;
                    discovered.add(new DiscoveredSegment(
                            number,
                            representation,
                            entry,
                            fileIdentity));
                }
            } catch (DirectoryIteratorException e) {
                throw new JournalStorageException(
                        IO_FAILURE, "Could not scan the session journal", e.getCause());
            }
            verifySessionDirectory(sessionDirectory, identity);
        } catch (IOException e) {
            throw new JournalStorageException(IO_FAILURE, "Could not scan the session journal", e);
        }
        return new Discovery(discovered, temporaryFiles);
    }

    private static List<DiscoveredPair> pairRepresentations(List<DiscoveredSegment> segments)
            throws JournalStorageException {
        List<DiscoveredPair> pairs = new ArrayList<>();
        int index = 0;
        while (index < segments.size()) {
            long number = segments.get(index).number();
            DiscoveredSegment uncompressed = null;
            DiscoveredSegment compressed = null;
            while (index < segments.size() && segments.get(index).number() == number) {
                DiscoveredSegment segment = segments.get(index++);
                if (segment.representation() == SegmentCatalog.Representation.UNCOMPRESSED) {
                    if (uncompressed != null) {
                        throw corruption("A journal segment has duplicate uncompressed entries");
                    }
                    uncompressed = segment;
                } else {
                    if (compressed != null) {
                        throw corruption("A journal segment has duplicate compressed entries");
                    }
                    compressed = segment;
                }
            }
            pairs.add(new DiscoveredPair(
                    number,
                    Optional.ofNullable(uncompressed),
                    Optional.ofNullable(compressed)));
        }
        return pairs;
    }

    private void validateNumbers(List<DiscoveredPair> segments) throws JournalStorageException {
        for (int index = 1; index < segments.size(); index++) {
            long previous = segments.get(index - 1).number();
            long current = segments.get(index).number();
            if (current != previous + 1) {
                throw corruption("The stored journal has a numeric segment gap");
            }
        }
    }

    private ReadSegment decode(
            Path path,
            SessionDirectoryIdentity identity,
            SegmentCatalog.FileIdentity segmentIdentity,
            SegmentCatalog.Representation representation,
            Optional<Long> byteLimit,
            Optional<byte[]> expectedDigest,
            boolean retainRecords) throws JournalStorageException {
        return decode(
                path,
                identity,
                segmentIdentity,
                representation,
                byteLimit,
                expectedDigest,
                new DecodedRecords(retainRecords));
    }

    private ReadSegment decode(
            Path path,
            SessionDirectoryIdentity identity,
            SegmentCatalog.FileIdentity segmentIdentity,
            SegmentCatalog.Representation representation,
            Optional<Long> byteLimit,
            Optional<byte[]> expectedDigest,
            DecodedRecords decodedRecords) throws JournalStorageException {
        SessionEventDecoder decoder = new SessionEventDecoder(limits);
        MessageDigest digest = sha256();
        long contentLength;
        byte[] actualDigest;
        if (byteLimit.isPresent() && byteLimit.get() > maxLogicalSegmentBytes) {
            throw corruption("A journal segment exceeds the configured logical length");
        }
        verifySessionDirectory(path.getParent(), identity);
        verifySegmentPrefix(
                path,
                segmentIdentity,
                representation == SegmentCatalog.Representation.UNCOMPRESSED
                        ? byteLimit
                        : Optional.empty());
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            verifySessionDirectory(path.getParent(), identity);
            verifySegment(path, segmentIdentity);
            if (representation == SegmentCatalog.Representation.UNCOMPRESSED
                    && byteLimit.isPresent()
                    && channel.size() < byteLimit.get()) {
                throw new IOException("A journal segment is shorter than its committed prefix");
            }
            if (representation == SegmentCatalog.Representation.UNCOMPRESSED
                    && channel.size() > maxLogicalSegmentBytes) {
                throw corruption("A journal segment exceeds the configured logical length");
            }
            InputStream observed = operations.observeReads(
                    path, Channels.newInputStream(channel));
            try (InputStream content = representation == SegmentCatalog.Representation.COMPRESSED
                    ? new ZstdCompressorInputStream(
                            new ZstdFrameInputStream(observed, maxZstdWindowBytes))
                    : observed) {
                InputStream input = new DigestInputStream(content, digest);
                verifySessionDirectory(path.getParent(), identity);
                verifySegment(path, segmentIdentity);
                if (byteLimit.isPresent()) {
                    contentLength = byteLimit.get();
                    decodeIncrementally(
                            input,
                            contentLength,
                            decoder,
                            limits.maxMessageBytes(),
                            decodedRecords::accept);
                    if (representation == SegmentCatalog.Representation.COMPRESSED
                            && input.read() != -1) {
                        throw corruption("A compressed journal segment exceeds its logical length");
                    }
                } else if (representation == SegmentCatalog.Representation.COMPRESSED) {
                    contentLength = decodeToEnd(
                            input,
                            decoder,
                            limits.maxMessageBytes(),
                            maxLogicalSegmentBytes,
                            decodedRecords::accept);
                } else {
                    contentLength = channel.size();
                    decodeIncrementally(
                            input,
                            contentLength,
                            decoder,
                            limits.maxMessageBytes(),
                            decodedRecords::accept);
                }
                actualDigest = digest.digest();
            }
            verifySessionDirectory(path.getParent(), identity);
            verifySegment(path, segmentIdentity);
            if (expectedDigest.isPresent()
                    && !MessageDigest.isEqual(expectedDigest.get(), actualDigest)) {
                throw corruption("A journal segment committed prefix changed while being read");
            }
        } catch (AgentProtocolException e) {
            throw new JournalStorageException(STORED_CORRUPTION, "A journal segment is malformed", e);
        } catch (IOException e) {
            if (representation == SegmentCatalog.Representation.COMPRESSED
                    && causedByInvalidZstd(e)) {
                throw new JournalStorageException(
                        STORED_CORRUPTION, "A compressed journal segment is malformed", e);
            }
            throw new JournalStorageException(IO_FAILURE, "Could not read a journal segment", e);
        }
        return new ReadSegment(
                decodedRecords.records(),
                Optional.ofNullable(decodedRecords.firstEventId()),
                Optional.ofNullable(decodedRecords.lastEventId()),
                contentLength - decoder.pendingBytes(),
                decoder.pendingBytes(),
                actualDigest);
    }

    private static boolean causedByInvalidZstd(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ZstdIOException) {
                return true;
            }
            if (current instanceof ZstdFrameInputStream.ZstdFrameValidationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long decodeToEnd(
            InputStream input,
            SessionEventDecoder decoder,
            int maxMessageBytes,
            long maxLogicalSegmentBytes,
            DecodedBatchHandler handler)
            throws IOException, AgentProtocolException, JournalStorageException {
        int readBytes = Math.min(INITIAL_READ_BYTES, maxMessageBytes);
        byte[] buffer = new byte[readBytes];
        long length = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            try {
                length = Math.addExact(length, read);
            } catch (ArithmeticException e) {
                throw corruption("A compressed journal segment logical length overflowed");
            }
            if (length > maxLogicalSegmentBytes) {
                throw corruption("A compressed journal segment exceeds its logical length limit");
            }
            acceptDecoded(
                    decoder.accept(ByteBuffer.wrap(buffer, 0, read)),
                    handler);
        }
        return length;
    }

    static void decodeIncrementally(
            InputStream input,
            long byteLimit,
            SessionEventDecoder decoder,
            int maxMessageBytes,
            DecodedBatchHandler handler)
            throws IOException, AgentProtocolException, JournalStorageException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(handler, "handler");
        if (byteLimit < 0) {
            throw new IllegalArgumentException("byteLimit must not be negative");
        }
        if (maxMessageBytes < 1) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }

        long remaining = byteLimit;
        int initialReadBytes = Math.min(INITIAL_READ_BYTES, maxMessageBytes);
        int nextReadBytes = initialReadBytes;
        while (remaining > 0) {
            int requested = (int) Math.min(remaining, nextReadBytes);
            byte[] bytes = input.readNBytes(requested);
            if (bytes.length == 0) {
                throw new JournalStorageException(
                        IO_FAILURE, "A journal segment became shorter while being read");
            }
            int decoded = acceptDecoded(decoder.accept(ByteBuffer.wrap(bytes)), handler);
            remaining -= bytes.length;
            if (decoded == 0 && decoder.pendingBytes() > 0) {
                nextReadBytes = growReadSize(nextReadBytes, maxMessageBytes);
            } else {
                nextReadBytes = initialReadBytes;
            }
        }
    }

    private static int acceptDecoded(
            SequenceDecodeResult<SessionEventRecord> result,
            DecodedBatchHandler handler)
            throws AgentProtocolException, JournalStorageException {
        List<SessionEventRecord> decoded = new ArrayList<>(result.outcomes().size());
        for (SequenceDecodeResult.Outcome<SessionEventRecord> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<SessionEventRecord> value) {
                decoded.add(value.value());
                continue;
            }
            if (!decoded.isEmpty()) {
                handler.accept(List.copyOf(decoded));
            }
            SequenceDecodeResult.Rejected<SessionEventRecord> rejected =
                    (SequenceDecodeResult.Rejected<SessionEventRecord>) outcome;
            throw rejected.issue().exception();
        }
        if (!decoded.isEmpty()) {
            handler.accept(List.copyOf(decoded));
        }
        if (result.terminalIssue().isPresent()) {
            throw result.terminalIssue().orElseThrow().exception();
        }
        return decoded.size();
    }

    private static int growReadSize(int current, int maximum) {
        if (current >= maximum || current > maximum / 2) {
            return maximum;
        }
        return current * 2;
    }

    private SessionDirectoryIdentity inspectSessionDirectory(Path path, boolean missingAllowed)
            throws JournalStorageException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            if (missingAllowed) {
                return null;
            }
            throw new JournalStorageException(IO_FAILURE, "The session journal disappeared", e);
        } catch (IOException e) {
            throw new JournalStorageException(IO_FAILURE, "Could not inspect the session journal", e);
        }
        if (!attributes.isDirectory()) {
            throw corruption("Session journal location is not a directory");
        }
        if (attributes.fileKey() == null) {
            throw new JournalStorageException(IO_FAILURE, "Could not establish session journal identity");
        }
        return new SessionDirectoryIdentity(attributes.fileKey());
    }

    private void verifySessionDirectory(Path path, SessionDirectoryIdentity expected)
            throws JournalStorageException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new JournalStorageException(IO_FAILURE, "Could not revalidate the session journal", e);
        }
        if (!attributes.isDirectory() || !Objects.equals(attributes.fileKey(), expected.fileKey())) {
            throw new JournalStorageException(IO_FAILURE, "The session journal changed while being read");
        }
    }

    private BasicFileAttributes inspectEntry(Path entry) throws JournalStorageException {
        try {
            return Files.readAttributes(
                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new JournalStorageException(IO_FAILURE, "Could not inspect a journal entry", e);
        }
    }

    private void verifySegment(Path path, SegmentCatalog.FileIdentity expected)
            throws JournalStorageException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new JournalStorageException(IO_FAILURE, "Could not revalidate a journal segment", e);
        }
        if (!attributes.isRegularFile()
                || attributes.fileKey() == null
                || !Objects.equals(attributes.fileKey(), expected.fileKey())) {
            throw new JournalStorageException(IO_FAILURE, "A journal segment changed while being read");
        }
    }

    private void verifySegmentPrefix(
            Path path,
            SegmentCatalog.FileIdentity expected,
            Optional<Long> minimumSize) throws JournalStorageException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new JournalStorageException(IO_FAILURE, "Could not revalidate a journal segment", e);
        }
        if (!attributes.isRegularFile()
                || attributes.fileKey() == null
                || !Objects.equals(attributes.fileKey(), expected.fileKey())) {
            throw new JournalStorageException(IO_FAILURE, "A journal segment changed while being read");
        }
        if (minimumSize.isPresent() && attributes.size() < minimumSize.get()) {
            throw new JournalStorageException(
                    IO_FAILURE, "A journal segment is shorter than its committed prefix");
        }
    }

    private static MessageDigest sha256() throws JournalStorageException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new JournalStorageException(
                    IO_FAILURE, "SHA-256 is unavailable for journal content validation", e);
        }
    }

    private static JournalStorageException corruption(String message) {
        return new JournalStorageException(STORED_CORRUPTION, message);
    }

    private record SessionDirectoryIdentity(Object fileKey) {
    }

    private record DiscoveredSegment(
            long number,
            SegmentCatalog.Representation representation,
            Path path,
            SegmentCatalog.FileIdentity identity) {
        private DurableFileOperations.CleanupToken cleanupToken() {
            return new DurableFileOperations.CleanupToken(path, identity);
        }
    }

    private record Discovery(
            List<DiscoveredSegment> segments,
            List<DurableFileOperations.CleanupToken> temporaryFiles) {
    }

    private record DiscoveredPair(
            long number,
            Optional<DiscoveredSegment> uncompressed,
            Optional<DiscoveredSegment> compressed) {
    }

    private record ScannedSegment(
            DiscoveredSegment selected,
            ReadSegment read,
            List<DurableFileOperations.CleanupToken> cleanupTokens) {
    }

    private record ReadSegment(
            List<SessionEventRecord> records,
            Optional<EventId> firstEventId,
            Optional<EventId> lastEventId,
            long completeByteLength,
            int incompleteBytes,
            byte[] digest) {
        private ReadSegment {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    record RecoveryScan(
            SegmentCatalog catalog,
            List<DurableFileOperations.RecoveryContent> contents,
            List<DurableFileOperations.CleanupToken> cleanupTokens) {
        RecoveryScan {
            contents = List.copyOf(contents);
            cleanupTokens = List.copyOf(cleanupTokens);
        }
    }

    record CompressedSegment(
            SegmentCatalog.Segment segment,
            DurableFileOperations.SegmentContent content) {
    }

    enum TransitionState {
        PRESENT,
        UNLINKING,
        ABSENT
    }

    record LayoutTransition(
            DurableFileOperations.CleanupToken cleanup,
            TransitionState state) {
        LayoutTransition {
            Objects.requireNonNull(cleanup, "cleanup");
            Objects.requireNonNull(state, "state");
        }
    }

    private record RequestedRecord(int index, EventId eventId) {
    }

    @FunctionalInterface
    interface DecodedBatchHandler {
        void accept(List<SessionEventRecord> records) throws JournalStorageException;
    }

    private static final class DecodedRecords {
        private final boolean retainAllRecords;
        private final List<EventId> requestedEventIds;
        private final Consumer<SessionEventRecord> retentionObserver;
        private final List<SessionEventRecord> records = new ArrayList<>();
        private int requestedIndex;
        private EventId firstEventId;
        private EventId lastEventId;

        private DecodedRecords(boolean retainRecords) {
            retainAllRecords = retainRecords;
            requestedEventIds = List.of();
            retentionObserver = ignored -> {
            };
        }

        private DecodedRecords(
                List<EventId> requestedEventIds,
                Consumer<SessionEventRecord> retentionObserver) {
            retainAllRecords = false;
            this.requestedEventIds = List.copyOf(requestedEventIds);
            this.retentionObserver = Objects.requireNonNull(
                    retentionObserver, "retentionObserver");
        }

        private void accept(List<SessionEventRecord> decoded) throws JournalStorageException {
            for (SessionEventRecord record : decoded) {
                if (lastEventId != null && lastEventId.compareTo(record.eventId()) >= 0) {
                    throw corruption("Stored event IDs are not strictly increasing");
                }
                if (firstEventId == null) {
                    firstEventId = record.eventId();
                }
                lastEventId = record.eventId();
                if (retainAllRecords) {
                    records.add(record);
                } else {
                    retainIfRequested(record);
                }
            }
        }

        private void retainIfRequested(SessionEventRecord record) {
            while (requestedIndex < requestedEventIds.size()
                    && requestedEventIds.get(requestedIndex).compareTo(record.eventId()) < 0) {
                requestedIndex++;
            }
            if (requestedIndex < requestedEventIds.size()
                    && requestedEventIds.get(requestedIndex).equals(record.eventId())) {
                records.add(record);
                retentionObserver.accept(record);
                requestedIndex++;
            }
        }

        private List<SessionEventRecord> records() {
            return List.copyOf(records);
        }

        private EventId firstEventId() {
            return firstEventId;
        }

        private EventId lastEventId() {
            return lastEventId;
        }
    }
}
