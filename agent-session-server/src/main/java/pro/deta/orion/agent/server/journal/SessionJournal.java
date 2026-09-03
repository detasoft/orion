package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.INVALID_APPEND;
import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.IO_FAILURE;
import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.CONFLICTING_DUPLICATE;

final class SessionJournal {
    private final Path sessionDirectory;
    private final SegmentReader reader;
    private final SessionEventCodec codec;
    private final DurableFileOperations operations;
    private final JournalMaintenance maintenance;
    private final SegmentCompressor compressor;
    private final int maxAppendRecords;
    private final int maxAppendBytes;
    private final long targetSegmentBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong failureEpoch = new AtomicLong();
    private final Map<DurableFileOperations.CleanupToken, SegmentReader.TransitionState> transitions =
            new LinkedHashMap<>();
    private final Set<Path> unboundTransitions = new HashSet<>();
    private final Map<DurableFileOperations.CleanupToken, Integer> readLeases = new HashMap<>();
    private SegmentCatalog catalog;
    private List<DurableFileOperations.SegmentContent> catalogContents = List.of();
    private long catalogGeneration;

    SessionJournal(
            Path sessionDirectory,
            JournalStorageConfig config,
            DurableFileOperations operations,
            JournalMaintenance maintenance) {
        this.sessionDirectory = sessionDirectory;
        reader = new SegmentReader(config, operations);
        codec = new SessionEventCodec(config.protocolLimits());
        this.operations = operations;
        this.maintenance = maintenance;
        compressor = new SegmentCompressor(reader, operations);
        maxAppendRecords = config.maxAppendRecords();
        maxAppendBytes = config.maxAppendBytes();
        targetSegmentBytes = Math.min(
                config.targetSegmentBytes(), config.maxLogicalSegmentBytes());
    }

    Optional<EventId> firstEventId() throws JournalStorageException {
        lock.lock();
        try {
            requireUsable();
            return catalog().firstEventId();
        } finally {
            boolean schedule = hasMaintenanceWorkLocked();
            lock.unlock();
            if (schedule) {
                maintenance.enqueue(this);
            }
        }
    }

    Optional<EventId> lastEventId() throws JournalStorageException {
        lock.lock();
        try {
            requireUsable();
            return catalog().lastEventId();
        } finally {
            boolean schedule = hasMaintenanceWorkLocked();
            lock.unlock();
            if (schedule) {
                maintenance.enqueue(this);
            }
        }
    }

    JournalAppendResult append(List<SessionEventRecord> records) throws JournalStorageException {
        acquireAppendLock();
        try {
            long appendEpoch = usableEpoch();
            Objects.requireNonNull(records, "records");
            if (records.size() > maxAppendRecords) {
                throw new JournalStorageException(
                        INVALID_APPEND, "Append record count exceeds the configured limit");
            }
            List<SessionEventRecord> batch = List.copyOf(records);
            ValidatedBatch validated = validateRecords(batch);
            validateRequestOrder(batch);
            try {
                SegmentCatalog current = catalog();
                if (batch.isEmpty()) {
                    requireEpoch(appendEpoch);
                    return new JournalAppendResult(current.lastEventId(), List.of());
                }
                int overlapCount = classifyOverlap(current, batch, appendEpoch);
                if (overlapCount == batch.size()) {
                    requireEpoch(appendEpoch);
                    return new JournalAppendResult(current.lastEventId(), List.of());
                }
                List<SessionEventRecord> newSuffix = List.copyOf(
                        batch.subList(overlapCount, batch.size()));
                return appendDurably(
                        current,
                        newSuffix,
                        validated.suffix(overlapCount),
                        appendEpoch);
            } catch (IOException e) {
                poison();
                throw new JournalStorageException(IO_FAILURE, "Could not durably append journal records", e);
            } catch (JournalStorageException e) {
                if (e.reason() != INVALID_APPEND
                        && e.reason() != JournalStorageException.Reason.CONFLICTING_DUPLICATE) {
                    poison();
                }
                throw e;
            }
        } finally {
            boolean schedule = hasMaintenanceWorkLocked();
            lock.unlock();
            if (schedule) {
                maintenance.enqueue(this);
            }
        }
    }

    private void acquireAppendLock() {
        if (!lock.tryLock()) {
            operations.appendLockContended(sessionDirectory);
            lock.lock();
        }
    }

    private int classifyOverlap(
            SegmentCatalog current,
            List<SessionEventRecord> batch,
            long appendEpoch) throws JournalStorageException {
        EventId durableThrough = current.lastEventId().orElse(null);
        if (durableThrough == null) {
            return 0;
        }
        int overlapCount = 0;
        while (overlapCount < batch.size()
                && batch.get(overlapCount).eventId().compareTo(durableThrough) <= 0) {
            overlapCount++;
        }
        if (overlapCount == 0) {
            return 0;
        }

        List<EventId> overlapIds = new ArrayList<>(overlapCount);
        for (int index = 0; index < overlapCount; index++) {
            overlapIds.add(batch.get(index).eventId());
        }
        List<Optional<SessionEventRecord>> stored = reader.locateRecords(
                current, catalogContents, overlapIds);
        requireEpoch(appendEpoch);
        for (int index = 0; index < overlapCount; index++) {
            SessionEventRecord storedRecord = stored.get(index).orElseThrow(
                    () -> new JournalStorageException(
                            INVALID_APPEND,
                            "Append event ID is below the durable cursor but is not stored"));
            if (!storedRecord.encodedRecord().equals(batch.get(index).encodedRecord())) {
                throw new JournalStorageException(
                        CONFLICTING_DUPLICATE,
                        "Append event ID is already stored with different encoded bytes");
            }
        }
        return overlapCount;
    }

    JournalReadResult readAfter(Optional<EventId> after) throws JournalStorageException {
        JournalSnapshot snapshot;
        lock.lock();
        try {
            requireUsable();
            SegmentCatalog current = catalog();
            List<DurableFileOperations.CleanupToken> leases = new ArrayList<>(
                    current.segments().size());
            for (SegmentCatalog.Segment segment : current.segments()) {
                DurableFileOperations.CleanupToken lease = new DurableFileOperations.CleanupToken(
                        segment.physicalPath(), segment.identity());
                readLeases.merge(lease, 1, Integer::sum);
                leases.add(lease);
            }
            snapshot = new JournalSnapshot(current, catalogContents, leases);
        } finally {
            boolean schedule = hasMaintenanceWorkLocked();
            lock.unlock();
            if (schedule) {
                maintenance.enqueue(this);
            }
        }

        List<SessionEventRecord> snapshotRecords;
        try {
            snapshotRecords = reader.readRecords(snapshot.catalog(), snapshot.contents());
        } catch (JournalStorageException e) {
            poison();
            operations.afterReadFailurePublished();
            lock.lock();
            try {
            } finally {
                lock.unlock();
            }
            throw e;
        } finally {
            releaseReadLease(snapshot.leases());
        }
        if (snapshotRecords.isEmpty()) {
            return new JournalReadResult(List.of(), Optional.empty());
        }

        EventId firstAvailable = snapshotRecords.getFirst().eventId();
        Optional<JournalGap> gap = after
                .filter(cursor -> cursor.compareTo(firstAvailable) < 0)
                .map(cursor -> new JournalGap(cursor, firstAvailable));
        if (after.isEmpty() || gap.isPresent()) {
            return new JournalReadResult(snapshotRecords, gap);
        }

        EventId cursor = after.get();
        List<SessionEventRecord> records = new ArrayList<>();
        for (SessionEventRecord record : snapshotRecords) {
            if (record.eventId().compareTo(cursor) > 0) {
                records.add(record);
            }
        }
        return new JournalReadResult(records, Optional.empty());
    }

    private JournalAppendResult appendDurably(
            SegmentCatalog current,
            List<SessionEventRecord> batch,
            ValidatedBatch validated,
            long appendEpoch) throws IOException, JournalStorageException {
        List<PlannedSegmentWrite> plannedWrites = planWrites(current, batch, validated);
        List<DurableFileOperations.SegmentContent> originalContents = catalogContents;
        DurableFileOperations.DirectoryTree directories = operations.createDirectories(sessionDirectory);
        boolean sessionWasMissing = current.sessionDirectoryIdentity().isEmpty();
        if (sessionWasMissing && !directories.created(sessionDirectory)) {
            throw new IOException("The session journal appeared while append was starting");
        }

        SegmentCatalog writable = sessionWasMissing
                ? new SegmentCatalog(
                        sessionDirectory,
                        Optional.of(directories.leaf().identity()),
                        List.of())
                : current;
        if (writable.sessionDirectoryIdentity().isEmpty()
                || !writable.sessionDirectoryIdentity().get().equals(directories.leaf().identity())) {
            throw new IOException("The session journal changed after directory preparation");
        }
        operations.verifyMetadata(writable, originalContents);

        List<SegmentCatalog.Segment> candidateSegments = new ArrayList<>(writable.segments());
        List<DurableFileOperations.SegmentContent> expectedContents = new ArrayList<>(
                originalContents);
        boolean predecessorForced = false;
        for (int writeIndex = 0; writeIndex < plannedWrites.size(); writeIndex++) {
            PlannedSegmentWrite planned = plannedWrites.get(writeIndex);
            if (planned.createNew() && !candidateSegments.isEmpty() && !predecessorForced) {
                SegmentCatalog.Segment predecessor = candidateSegments.getLast();
                reader.verifyAppendTarget(
                        writable, predecessor.physicalPath(), predecessor.identity());
                operations.forceFile(expectedContents.getLast());
                reader.verifyAppendTarget(
                        writable, predecessor.physicalPath(), predecessor.identity());
                predecessorForced = true;
            }
            WrittenSegment written = writeSegment(writable, directories, planned, originalContents);
            if (planned.createNew()) {
                candidateSegments.add(written.segment());
                expectedContents.add(written.content());
            } else {
                candidateSegments.set(candidateSegments.size() - 1, written.segment());
                expectedContents.set(expectedContents.size() - 1, written.content());
            }
            predecessorForced = true;
            if (planned.createNew() && writeIndex + 1 < plannedWrites.size()) {
                operations.forceDirectory(directories.leaf());
                operations.verifyDirectories(directories);
            }
        }

        for (DurableFileOperations.DurableDirectory directory : directories.chain()) {
            operations.forceDirectory(directory);
        }
        operations.verifyDirectories(directories);

        SegmentCatalog next = new SegmentCatalog(
                sessionDirectory,
                Optional.of(directories.leaf().identity()),
                candidateSegments);
        List<DurableFileOperations.SegmentContent> immutableContents = List.copyOf(expectedContents);
        verifyPublicationState(directories, next, immutableContents);
        requireEpoch(appendEpoch);
        boolean[] assigned = {false};
        try {
            operations.publishCatalog(() -> {
                if (failureEpoch.get() == appendEpoch) {
                    publishCatalog(next, immutableContents);
                    assigned[0] = true;
                }
            });
            requireEpoch(appendEpoch);
            if (!assigned[0]) {
                throw new JournalStorageException(
                        IO_FAILURE, "The session journal failed before catalog publication");
            }
            verifyPublicationState(directories, next, immutableContents);
            requireEpoch(appendEpoch);
            return new JournalAppendResult(next.lastEventId(), batch);
        } catch (IOException | JournalStorageException e) {
            if (assigned[0]) {
                publishCatalog(current, originalContents);
            }
            throw e;
        }
    }

    private WrittenSegment writeSegment(
            SegmentCatalog writable,
            DurableFileOperations.DirectoryTree directories,
            PlannedSegmentWrite planned,
            List<DurableFileOperations.SegmentContent> originalContents)
            throws IOException, JournalStorageException {
        Optional<SegmentCatalog.Segment> activeSegment = planned.createNew()
                ? Optional.empty()
                : Optional.of(writable.segments().getLast());
        Optional<DurableFileOperations.SegmentContent> activeContent = planned.createNew()
                ? Optional.empty()
                : Optional.of(originalContents.getLast());
        try (DurableFileOperations.AppendFile opened = operations.openAppend(
                planned.path(), planned.createNew(), directories.leaf())) {
            FileChannel channel = opened.channel();
            if (activeSegment.isPresent()
                    && !activeSegment.get().identity().equals(opened.identity())) {
                throw new IOException("The active journal segment changed while append was starting");
            }
            reader.verifyAppendTarget(writable, planned.path(), opened.identity());
            if (channel.size() != planned.prefixLength()) {
                throw new IOException("The active journal segment has an unexpected length");
            }
            DurableFileOperations.FileGeneration prefixGeneration = operations.captureGeneration(
                    planned.path(), opened.identity(), planned.prefixLength());
            if (activeContent.isPresent()
                    && !prefixGeneration.equals(activeContent.get().physical().generation())) {
                throw new IOException("The active journal segment generation changed before append");
            }
            channel.position(planned.prefixLength());
            for (byte[] bytes : planned.encodedRecords()) {
                writeFully(channel, bytes);
            }
            operations.forceFile(channel);
            reader.verifyAppendTarget(writable, planned.path(), opened.identity());
            DurableFileOperations.FileGeneration nextGeneration = operations.captureGeneration(
                    planned.path(), opened.identity(), planned.nextLength());
            byte[] expectedDigest = operations.verifyAppendedContent(
                    planned.path(),
                    opened.identity(),
                    planned.prefixLength(),
                    activeContent.map(DurableFileOperations.SegmentContent::digest),
                    nextGeneration,
                    planned.encodedRecords());
            return new WrittenSegment(
                    expectedSegment(planned, activeSegment, opened.identity()),
                    new DurableFileOperations.SegmentContent(
                            planned.path(),
                            opened.identity(),
                            planned.nextLength(),
                            expectedDigest,
                            nextGeneration));
        }
    }

    private SegmentCatalog.Segment expectedSegment(
            PlannedSegmentWrite planned,
            Optional<SegmentCatalog.Segment> previous,
            SegmentCatalog.FileIdentity identity) {
        Optional<EventId> first = previous.isPresent() && previous.get().firstEventId().isPresent()
                ? previous.get().firstEventId()
                : Optional.of(planned.records().getFirst().eventId());
        return new SegmentCatalog.Segment(
                planned.number(),
                first,
                Optional.of(planned.records().getLast().eventId()),
                SegmentCatalog.Representation.UNCOMPRESSED,
                planned.path(),
                planned.nextLength(),
                identity);
    }

    private List<PlannedSegmentWrite> planWrites(
            SegmentCatalog current,
            List<SessionEventRecord> batch,
            ValidatedBatch validated) throws IOException {
        List<PlannedSegmentWriteBuilder> builders = new ArrayList<>();
        boolean createNew = current.segments().isEmpty()
                || current.segments().getLast().representation()
                == SegmentCatalog.Representation.COMPRESSED;
        long number = current.segments().isEmpty()
                ? 1
                : createNew
                        ? nextSegmentNumber(current.segments().getLast().number())
                        : current.segments().getLast().number();
        long length = createNew ? 0 : current.segments().getLast().completeByteLength();
        Path path = createNew
                ? segmentPath(number)
                : current.segments().getLast().physicalPath();

        for (int index = 0; index < batch.size(); index++) {
            byte[] encoded = validated.encodedRecords().get(index);
            if (length > 0 && wouldExceedTarget(length, encoded.length)) {
                if (number >= 99_999_999L) {
                    throw new IOException("The journal segment number exceeds its eight-digit representation");
                }
                number++;
                length = 0;
                createNew = true;
                path = segmentPath(number);
            }
            if (builders.isEmpty() || builders.getLast().number != number) {
                builders.add(new PlannedSegmentWriteBuilder(number, path, createNew, length));
            }
            builders.getLast().add(batch.get(index), encoded);
            try {
                length = Math.addExact(length, encoded.length);
            } catch (ArithmeticException e) {
                throw new IOException("The active journal segment length overflowed", e);
            }
            builders.getLast().nextLength = length;
            createNew = false;
        }

        List<PlannedSegmentWrite> writes = new ArrayList<>(builders.size());
        for (PlannedSegmentWriteBuilder builder : builders) {
            writes.add(builder.build());
        }
        return List.copyOf(writes);
    }

    private static long nextSegmentNumber(long number) throws IOException {
        if (number >= 99_999_999L) {
            throw new IOException("The journal segment number exceeds its eight-digit representation");
        }
        return number + 1;
    }

    private boolean wouldExceedTarget(long currentLength, int recordLength) {
        return currentLength > targetSegmentBytes
                || recordLength > targetSegmentBytes - currentLength;
    }

    private Path segmentPath(long number) {
        return sessionDirectory.resolve(String.format(Locale.ROOT, "%08d.cbor", number));
    }

    private void verifyPublicationState(
            DurableFileOperations.DirectoryTree directories,
            SegmentCatalog candidate,
            List<DurableFileOperations.SegmentContent> expectedContents)
            throws IOException, JournalStorageException {
        operations.verifyDirectories(directories);
        reader.verifyLayout(candidate, layoutTransitions());
        operations.verifyMetadata(candidate, expectedContents);
    }

    private ValidatedBatch validateRecords(List<SessionEventRecord> batch)
            throws JournalStorageException {
        if (batch.size() > maxAppendRecords) {
            throw new JournalStorageException(
                    INVALID_APPEND, "Append record count exceeds the configured limit");
        }
        List<byte[]> encoded = new ArrayList<>(batch.size());
        long encodedLength = 0;
        for (SessionEventRecord record : batch) {
            int recordLength = record.encodedRecord().size();
            if (recordLength > maxAppendBytes - encodedLength) {
                throw new JournalStorageException(
                        INVALID_APPEND, "Append encoded bytes exceed the configured limit");
            }
            byte[] bytes = record.encodedRecord().toByteArray();
            SessionEventRecord decoded;
            try {
                decoded = codec.decode(bytes);
                encodedLength = Math.addExact(encodedLength, bytes.length);
            } catch (AgentProtocolException | ArithmeticException e) {
                throw new JournalStorageException(
                        INVALID_APPEND, "Append contains an invalid encoded session event", e);
            }
            if (!decoded.equals(record)) {
                throw new JournalStorageException(
                        INVALID_APPEND, "Append event metadata does not match its encoded record");
            }
            encoded.add(bytes);
        }
        return new ValidatedBatch(encoded, encodedLength);
    }

    private void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int written = operations.write(channel, buffer);
            if (written <= 0) {
                throw new IOException("Journal append made no progress");
            }
        }
    }

    private static void validateRequestOrder(List<SessionEventRecord> records)
            throws JournalStorageException {
        EventId previous = null;
        for (SessionEventRecord record : records) {
            if (previous != null && previous.compareTo(record.eventId()) >= 0) {
                throw new JournalStorageException(
                        INVALID_APPEND, "Append event IDs must be strictly increasing");
            }
            previous = record.eventId();
        }
    }

    private static boolean sameCatalog(SegmentCatalog expected, SegmentCatalog actual) {
        return expected.sessionDirectory().equals(actual.sessionDirectory())
                && expected.sessionDirectoryIdentity().equals(actual.sessionDirectoryIdentity())
                && expected.segments().equals(actual.segments());
    }

    private void requireUsable() throws JournalStorageException {
        if (failureEpoch.get() != 0) {
            throw new JournalStorageException(
                    IO_FAILURE, "The session journal is unavailable after a failed append");
        }
    }

    private long usableEpoch() throws JournalStorageException {
        long epoch = failureEpoch.get();
        requireEpoch(epoch);
        return epoch;
    }

    private void requireEpoch(long expected) throws JournalStorageException {
        if (expected != 0 || failureEpoch.get() != expected) {
            throw new JournalStorageException(
                    IO_FAILURE, "The session journal became unavailable during append");
        }
    }

    private void poison() {
        failureEpoch.compareAndSet(0, 1);
    }

    void performMaintenance(BooleanSupplier accepting)
            throws IOException, JournalStorageException {
        while (accepting.getAsBoolean()) {
            DurableFileOperations.CleanupToken cleanup = pendingCleanup();
            if (cleanup != null) {
                cleanup(cleanup);
                continue;
            }
            if (hasTransitions()) {
                return;
            }
            CompressionTarget target = compressionTarget();
            if (target == null) {
                return;
            }
            compressor.compress(this, target, accepting);
        }
    }

    RegisteredTemporary createTemporary(
            Path path,
            DurableFileOperations.DurableDirectory directory)
            throws IOException, JournalStorageException {
        lock.lock();
        try {
            requireUsable();
            reserveUnboundTransition(path);
            boolean[] created = new boolean[1];
            DurableFileOperations.CleanupToken[] registered = new DurableFileOperations.CleanupToken[1];
            try {
                DurableFileOperations.AppendFile file = operations.openAppend(
                        path,
                        true,
                        directory,
                        () -> created[0] = true,
                        identity -> registered[0] = bindTemporaryIdentity(path, identity));
                if (registered[0] == null) {
                    file.close();
                    throw new IOException("Compression temporary identity was not registered");
                }
                return new RegisteredTemporary(file, registered[0]);
            } catch (IOException | RuntimeException failure) {
                if (created[0] && registered[0] == null) {
                    try {
                        registered[0] = bindTemporaryIdentity(
                                path, operations.captureIdentity(path));
                    } catch (IOException | RuntimeException registrationFailure) {
                        failure.addSuppressed(registrationFailure);
                    }
                }
                if (!created[0]) {
                    unboundTransitions.remove(path);
                }
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    private void reserveUnboundTransition(Path path) throws IOException {
        for (DurableFileOperations.CleanupToken transition : transitions.keySet()) {
            if (transition.path().equals(path)) {
                throw new IOException("Journal transition path was already registered");
            }
        }
        if (!unboundTransitions.add(path)) {
            throw new IOException("Journal transition path was already reserved");
        }
    }

    private DurableFileOperations.CleanupToken bindTemporaryIdentity(
            Path path,
            SegmentCatalog.FileIdentity identity) throws IOException {
        if (!unboundTransitions.remove(path)) {
            throw new IOException("Compression temporary path was not reserved");
        }
        DurableFileOperations.CleanupToken token =
                new DurableFileOperations.CleanupToken(path, identity);
        if (transitions.putIfAbsent(token, SegmentReader.TransitionState.PRESENT) != null) {
            throw new IOException("Compression temporary identity was already registered");
        }
        return token;
    }

    private void resolveUnboundTransitions() throws IOException {
        for (Path path : List.copyOf(unboundTransitions)) {
            Optional<SegmentCatalog.FileIdentity> identity =
                    operations.captureIdentityIfPresent(path);
            if (identity.isPresent()) {
                bindTemporaryIdentity(path, identity.get());
                continue;
            }
            operations.verifyAbsent(path);
            unboundTransitions.remove(path);
        }
    }

    private DurableFileOperations.CleanupToken pendingCleanup() throws IOException {
        lock.lock();
        try {
            resolveUnboundTransitions();
            if (transitions.isEmpty()) {
                return null;
            }
            for (DurableFileOperations.CleanupToken transition : transitions.keySet()) {
                if (!readLeases.containsKey(transition)) {
                    return transition;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private boolean hasTransitions() throws IOException {
        lock.lock();
        try {
            resolveUnboundTransitions();
            return !transitions.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private void cleanup(DurableFileOperations.CleanupToken cleanup) throws IOException {
        DurableFileOperations.DirectoryTree directories = operations.createDirectories(sessionDirectory);
        cleanupTransition(cleanup, directories.leaf());
    }

    private CompressionTarget compressionTarget() throws JournalStorageException {
        lock.lock();
        try {
            requireUsable();
            SegmentCatalog current = catalog();
            for (int index = 0; index + 1 < current.segments().size(); index++) {
                SegmentCatalog.Segment segment = current.segments().get(index);
                if (segment.representation() == SegmentCatalog.Representation.UNCOMPRESSED) {
                    return new CompressionTarget(
                            current,
                            catalogContents,
                            catalogGeneration,
                            index,
                            segment,
                            catalogContents.get(index));
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    boolean publishCompression(
            CompressionTarget target,
            SegmentReader.CompressedSegment replacement,
            BooleanSupplier accepting) throws IOException {
        List<SegmentCatalog.Segment> segments = new ArrayList<>(target.catalog().segments());
        List<DurableFileOperations.SegmentContent> contents = new ArrayList<>(target.contents());
        segments.set(target.index(), replacement.segment());
        contents.set(target.index(), replacement.content());
        SegmentCatalog candidate = new SegmentCatalog(
                target.catalog().sessionDirectory(),
                target.catalog().sessionDirectoryIdentity(),
                segments);
        lock.lock();
        try {
            if (!accepting.getAsBoolean()
                    || catalog == null
                    || target.catalogGeneration() != catalogGeneration
                    || target.index() >= catalogContents.size()
                    || !sameContent(target.content(), catalogContents.get(target.index()))) {
                return false;
            }
            operations.verifyMetadata(target.content());
            operations.verifyMetadata(replacement.content());
            DurableFileOperations.CleanupToken sourceTransition =
                    new DurableFileOperations.CleanupToken(
                            target.segment().physicalPath(), target.segment().identity());
            transitions.put(sourceTransition, SegmentReader.TransitionState.PRESENT);
            try {
                operations.beforeCompressionCatalogPublication();
            } catch (IOException e) {
                transitions.remove(sourceTransition);
                throw e;
            }
            publishCatalog(candidate, contents);
            transitions.remove(new DurableFileOperations.CleanupToken(
                    replacement.segment().physicalPath(), replacement.segment().identity()));
            return true;
        } finally {
            lock.unlock();
        }
    }

    void reserveAbsentTransition(DurableFileOperations.CleanupToken cleanup) {
        lock.lock();
        try {
            SegmentReader.TransitionState previous = transitions.putIfAbsent(
                    cleanup, SegmentReader.TransitionState.ABSENT);
            if (previous != null) {
                throw new IllegalStateException("Journal transition was already registered");
            }
        } finally {
            lock.unlock();
        }
    }

    void publishReservedLink(
            Path temporary,
            DurableFileOperations.CleanupToken published) throws IOException {
        lock.lock();
        try {
            if (transitions.get(published) != SegmentReader.TransitionState.ABSENT) {
                throw new IOException("Compressed journal publication was not reserved");
            }
            operations.publishLink(temporary, published.path());
            transitions.put(published, SegmentReader.TransitionState.PRESENT);
        } finally {
            lock.unlock();
        }
    }

    void cancelAbsentTransition(DurableFileOperations.CleanupToken cleanup) throws IOException {
        lock.lock();
        try {
            if (transitions.get(cleanup) != SegmentReader.TransitionState.ABSENT) {
                throw new IOException("Journal transition is not absent");
            }
            operations.verifyAbsent(cleanup.path());
            transitions.remove(cleanup);
        } finally {
            lock.unlock();
        }
    }

    void cleanupTransition(
            DurableFileOperations.CleanupToken cleanup,
            DurableFileOperations.DurableDirectory directory) throws IOException {
        boolean delete;
        boolean retryingUnlink;
        lock.lock();
        try {
            SegmentReader.TransitionState state = transitions.get(cleanup);
            if (state == null) {
                throw new IOException("Journal transition is not registered");
            }
            if (catalog != null) {
                for (SegmentCatalog.Segment segment : catalog.segments()) {
                    if (segment.physicalPath().equals(cleanup.path())) {
                        throw new IOException("Refusing to delete a cataloged journal segment");
                    }
                }
            }
            retryingUnlink = state == SegmentReader.TransitionState.UNLINKING;
            delete = state != SegmentReader.TransitionState.ABSENT;
            if (state == SegmentReader.TransitionState.PRESENT) {
                transitions.put(cleanup, SegmentReader.TransitionState.UNLINKING);
            }
        } finally {
            lock.unlock();
        }
        if (retryingUnlink && !operations.isPresent(cleanup)) {
            delete = false;
        }
        if (delete) {
            boolean deleted = operations.delete(cleanup);
            if (!deleted && !retryingUnlink) {
                throw new IOException("A present journal transition disappeared before cleanup");
            }
        }
        if (delete || retryingUnlink) {
            lock.lock();
            try {
                if (transitions.get(cleanup) != SegmentReader.TransitionState.UNLINKING) {
                    throw new IOException("Journal transition changed while it was being unlinked");
                }
                transitions.put(cleanup, SegmentReader.TransitionState.ABSENT);
            } finally {
                lock.unlock();
            }
        }
        operations.forceDirectory(directory);
        lock.lock();
        try {
            operations.verifyAbsent(cleanup.path());
            if (transitions.get(cleanup) != SegmentReader.TransitionState.ABSENT) {
                throw new IOException("Journal transition changed before cleanup completion");
            }
            transitions.remove(cleanup);
        } finally {
            lock.unlock();
        }
    }

    private void releaseReadLease(List<DurableFileOperations.CleanupToken> leases) {
        boolean schedule;
        lock.lock();
        try {
            for (DurableFileOperations.CleanupToken lease : leases) {
                Integer count = readLeases.get(lease);
                if (count == null) {
                    throw new IllegalStateException("Journal read lease was not registered");
                }
                if (count == 1) {
                    readLeases.remove(lease);
                } else {
                    readLeases.put(lease, count - 1);
                }
            }
            schedule = hasMaintenanceWorkLocked();
        } finally {
            lock.unlock();
        }
        if (schedule) {
            maintenance.enqueue(this);
        }
    }

    private boolean hasMaintenanceWorkLocked() {
        if (!unboundTransitions.isEmpty() || !transitions.isEmpty()) {
            return true;
        }
        if (catalog == null) {
            return false;
        }
        for (int index = 0; index + 1 < catalog.segments().size(); index++) {
            if (catalog.segments().get(index).representation()
                    == SegmentCatalog.Representation.UNCOMPRESSED) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameContent(
            DurableFileOperations.SegmentContent expected,
            DurableFileOperations.SegmentContent actual) {
        return expected.path().equals(actual.path())
                && expected.identity().equals(actual.identity())
                && expected.length() == actual.length()
                && java.security.MessageDigest.isEqual(expected.digest(), actual.digest())
                && expected.physical().length() == actual.physical().length()
                && expected.physical().generation().equals(actual.physical().generation())
                && java.security.MessageDigest.isEqual(
                        expected.physical().digest(), actual.physical().digest());
    }

    private SegmentCatalog catalog() throws JournalStorageException {
        if (catalog == null) {
            try {
                SegmentCatalog recovered = recoverCatalog();
                if (catalog == null) {
                    publishCatalog(recovered, List.of());
                }
            } catch (IOException e) {
                poison();
                throw new JournalStorageException(
                        IO_FAILURE, "Could not establish recovered journal durability", e);
            } catch (JournalStorageException e) {
                poison();
                throw e;
            }
        }
        return catalog;
    }

    private SegmentCatalog recoverCatalog() throws IOException, JournalStorageException {
        SegmentReader.RecoveryScan scan = reader.recover(sessionDirectory);
        SegmentCatalog recovered = scan.catalog();
        if (recovered.segments().isEmpty()) {
            registerRecoveredTransitions(scan.cleanupTokens());
            return recovered;
        }

        List<DurableFileOperations.RecoveryContent> recoveryContents =
                scan.contents();
        DurableFileOperations.DirectoryTree directories = operations.createDirectories(sessionDirectory);
        if (recovered.sessionDirectoryIdentity().isEmpty()
                || !recovered.sessionDirectoryIdentity().get().equals(directories.leaf().identity())) {
            throw new IOException("The recovered session journal changed before its durability barrier");
        }

        List<DurableFileOperations.RecoveryContent> durableContents = new ArrayList<>(
                recoveryContents);
        DurableFileOperations.RecoveryContent active = recoveryContents.getLast();
        if (recovered.segments().getLast().representation()
                == SegmentCatalog.Representation.UNCOMPRESSED
                && active.content().physical().length() > active.content().length()) {
            SegmentCatalog.Segment activeSegment = recovered.segments().getLast();
            reader.verifyAppendTarget(
                    recovered, activeSegment.physicalPath(), activeSegment.identity());
            DurableFileOperations.FileGeneration truncatedGeneration = operations.truncate(
                    active.content(), active.content().length());
            reader.verifyAppendTarget(
                    recovered, activeSegment.physicalPath(), activeSegment.identity());
            DurableFileOperations.SegmentContent truncated = new DurableFileOperations.SegmentContent(
                    active.content().path(),
                    active.content().identity(),
                    active.content().length(),
                    active.content().digest(),
                    truncatedGeneration);
            durableContents.set(
                    durableContents.size() - 1,
                    new DurableFileOperations.RecoveryContent(
                            truncated, SegmentCatalog.Representation.UNCOMPRESSED));
        }

        for (DurableFileOperations.RecoveryContent content : durableContents) {
            operations.forceFile(content.content());
        }
        for (DurableFileOperations.DurableDirectory directory : directories.chain()) {
            operations.forceDirectory(directory);
        }

        SegmentReader.RecoveryScan confirmedScan = reader.recover(sessionDirectory);
        SegmentCatalog confirmed = confirmedScan.catalog();
        if (!sameCatalog(recovered, confirmed)) {
            throw new IOException("The recovered journal catalog changed during its durability barrier");
        }
        verifyRecoveryPublicationState(
                directories, confirmed, durableContents, confirmedScan.cleanupTokens());
        List<DurableFileOperations.SegmentContent> committedContents = new ArrayList<>(
                durableContents.size());
        for (DurableFileOperations.RecoveryContent content : durableContents) {
            committedContents.add(content.content());
        }
        operations.publishCatalog(() -> publishCatalog(confirmed, committedContents));
        verifyRecoveryPublicationState(
                directories, confirmed, durableContents, confirmedScan.cleanupTokens());
        registerRecoveredTransitions(confirmedScan.cleanupTokens());
        return confirmed;
    }

    private void verifyRecoveryPublicationState(
            DurableFileOperations.DirectoryTree directories,
            SegmentCatalog candidate,
            List<DurableFileOperations.RecoveryContent> expectedContents,
            List<DurableFileOperations.CleanupToken> toleratedCleanup)
            throws IOException, JournalStorageException {
        operations.verifyDirectories(directories);
        reader.verifyLayout(candidate, presentTransitions(toleratedCleanup));
        operations.verifyRecoveryContents(candidate, expectedContents);
    }

    private void publishCatalog(
            SegmentCatalog published,
            List<DurableFileOperations.SegmentContent> contents) {
        catalog = published;
        catalogContents = List.copyOf(contents);
        catalogGeneration++;
    }

    private void registerRecoveredTransitions(
            List<DurableFileOperations.CleanupToken> cleanupTokens) {
        for (DurableFileOperations.CleanupToken cleanup : cleanupTokens) {
            transitions.put(cleanup, SegmentReader.TransitionState.PRESENT);
        }
    }

    private List<SegmentReader.LayoutTransition> layoutTransitions() throws IOException {
        resolveUnboundTransitions();
        List<SegmentReader.LayoutTransition> result = new ArrayList<>(transitions.size());
        for (Map.Entry<DurableFileOperations.CleanupToken, SegmentReader.TransitionState> entry
                : transitions.entrySet()) {
            result.add(new SegmentReader.LayoutTransition(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static List<SegmentReader.LayoutTransition> presentTransitions(
            List<DurableFileOperations.CleanupToken> cleanupTokens) {
        List<SegmentReader.LayoutTransition> result = new ArrayList<>(cleanupTokens.size());
        for (DurableFileOperations.CleanupToken cleanup : cleanupTokens) {
            result.add(new SegmentReader.LayoutTransition(
                    cleanup, SegmentReader.TransitionState.PRESENT));
        }
        return List.copyOf(result);
    }

    private record PlannedSegmentWrite(
            long number,
            Path path,
            boolean createNew,
            long prefixLength,
            long nextLength,
            List<SessionEventRecord> records,
            List<byte[]> encodedRecords) {
        private PlannedSegmentWrite {
            records = List.copyOf(records);
            encodedRecords = List.copyOf(encodedRecords);
        }
    }

    private static final class PlannedSegmentWriteBuilder {
        private final long number;
        private final Path path;
        private final boolean createNew;
        private final long prefixLength;
        private final List<SessionEventRecord> records = new ArrayList<>();
        private final List<byte[]> encodedRecords = new ArrayList<>();
        private long nextLength;

        private PlannedSegmentWriteBuilder(
                long number,
                Path path,
                boolean createNew,
                long prefixLength) {
            this.number = number;
            this.path = path;
            this.createNew = createNew;
            this.prefixLength = prefixLength;
            nextLength = prefixLength;
        }

        private void add(SessionEventRecord record, byte[] encoded) {
            records.add(record);
            encodedRecords.add(encoded);
        }

        private PlannedSegmentWrite build() {
            return new PlannedSegmentWrite(
                    number,
                    path,
                    createNew,
                    prefixLength,
                    nextLength,
                    records,
                    encodedRecords);
        }
    }

    private record WrittenSegment(
            SegmentCatalog.Segment segment,
            DurableFileOperations.SegmentContent content) {
    }

    private record ValidatedBatch(List<byte[]> encodedRecords, long encodedLength) {
        private ValidatedBatch {
            encodedRecords = List.copyOf(encodedRecords);
        }

        private ValidatedBatch suffix(int firstIndex) {
            List<byte[]> suffix = encodedRecords.subList(firstIndex, encodedRecords.size());
            long suffixLength = 0;
            for (byte[] bytes : suffix) {
                suffixLength += bytes.length;
            }
            return new ValidatedBatch(suffix, suffixLength);
        }
    }

    private record JournalSnapshot(
            SegmentCatalog catalog,
            List<DurableFileOperations.SegmentContent> contents,
            List<DurableFileOperations.CleanupToken> leases) {
        private JournalSnapshot {
            contents = List.copyOf(contents);
            leases = List.copyOf(leases);
        }
    }

    record CompressionTarget(
            SegmentCatalog catalog,
            List<DurableFileOperations.SegmentContent> contents,
            long catalogGeneration,
            int index,
            SegmentCatalog.Segment segment,
            DurableFileOperations.SegmentContent content) {
    }

    record RegisteredTemporary(
            DurableFileOperations.AppendFile file,
            DurableFileOperations.CleanupToken cleanup) {
    }
}
