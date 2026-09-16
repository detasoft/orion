package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.ByteArrayDataType;
import org.h2.mvstore.type.LongDataType;
import org.h2.mvstore.type.StringDataType;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackIndex;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Disk-backed metadata and waiting dependencies for one upload, with a four-MiB MVStore page cache.
 * Changes are committed in fixed batches of 256 operations; no whole-index Java collection or unresolved
 * dependency graph is retained. Indexes by object ID and dependency prefix provide direct lookups.
 * Exact repeated registration is harmless; validation precedes changes to records and waiting dependencies.
 * Finalization scans records incrementally, derives external bases, forces the index, and freezes mutations.
 * Only externalBaseIds materializes its requested final list, as required by PackIndex's return contract.
 * Cycles are rejected: choosing their external base requires evidence from published storage. Traversal
 * marks stay on disk, so neither deep chains nor branching dependencies require an in-memory graph.
 * Reopening requires a finalized index and opens it read-only. Storage failures are IOException and close
 * the failed handle without committing unfinished changes. File deletion and publication belong to storage.
 */
final class FilePackIndex implements PackIndex, AutoCloseable {
    private static final List<String> MAP_NAMES = List.of("entries", "objects", "unresolved",
            "waiting-id", "waiting-offset", "external-bases", "state");

    private final MVStore store;
    private final MVMap<Long, byte[]> entries;
    private final MVMap<String, Long> objects;
    private final MVMap<Long, Long> unresolved;
    private final MVMap<String, Long> waitingIds;
    private final MVMap<String, Long> waitingOffsets;
    private final MVMap<String, Long> externalBases;
    private final MVMap<String, Long> state;
    private int pendingChanges;

    static FilePackIndex create(Path path) throws IOException {
        Files.createFile(path);
        try {
            return load(path, true);
        } catch (IOException | RuntimeException | Error error) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    static FilePackIndex open(Path path) throws IOException {
        Files.size(path);
        return load(path, false);
    }

    private static FilePackIndex load(Path path, boolean create) throws IOException {
        MVStore store = null;
        try {
            var builder = new MVStore.Builder().fileName(path.toAbsolutePath().toString())
                    .cacheSize(4).autoCommitDisabled().autoCommitBufferSize(0);
            if (!create) {
                builder.readOnly();
            }
            store = builder.open();
            if (!create) {
                if (store.getStoreVersion() != 1) {
                    throw new IOException("Unsupported pack index version");
                }
                for (String name : MAP_NAMES) {
                    if (!store.hasMap(name)) {
                        throw new IOException("Missing pack index map: " + name);
                    }
                }
            }
            var index = new FilePackIndex(store);
            if (create) {
                store.setStoreVersion(1);
                index.state.put("finalized", 0L);
                store.commit();
            } else if (!index.finalized()) {
                throw new IOException("Pack index is not finalized");
            }
            return index;
        } catch (IOException | RuntimeException | Error error) {
            if (store != null) {
                closeFailed(store, error);
            }
            if (error instanceof MVStoreException) {
                throw new IOException("Cannot open pack index", error);
            }
            throw error;
        }
    }

    private FilePackIndex(MVStore store) {
        this.store = store;
        entries = store.openMap("entries", new MVMap.Builder<Long, byte[]>()
                .keyType(LongDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
        unresolved = store.openMap("unresolved", new MVMap.Builder<Long, Long>()
                .keyType(LongDataType.INSTANCE).valueType(LongDataType.INSTANCE));
        objects = stringMap("objects");
        waitingIds = stringMap("waiting-id");
        waitingOffsets = stringMap("waiting-offset");
        externalBases = stringMap("external-bases");
        state = stringMap("state");
    }

    private MVMap<String, Long> stringMap(String name) {
        return store.openMap(name, new MVMap.Builder<String, Long>()
                .keyType(StringDataType.INSTANCE).valueType(LongDataType.INSTANCE));
    }

    void finish() throws IOException {
        requireOpen();
        try {
            if (finalized()) {
                store.sync();
                return;
            }
            if (hasUnresolved()) {
                throw new IOException("Pack index contains unresolved records or dependencies");
            }
            externalBases.clear();
            var visited = store.openMap("finalization-walk", new MVMap.Builder<Long, Long>()
                    .keyType(LongDataType.INSTANCE).valueType(LongDataType.INSTANCE));
            try {
                var offsets = entries.keyIterator(null);
                while (offsets.hasNext()) {
                    inspectChain(offsets.next(), visited);
                }
            } finally {
                store.removeMap(visited);
            }
            state.put("finalized", 1L);
            store.commit();
            store.sync();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public void addEntry(PackObjectParser.Entry entry) throws IOException {
        requireOpen();
        Objects.requireNonNull(entry, "entry");
        try {
            requireMutable();
            validateEntry(entry);
            Record previous = record(entry.offset());
            if (previous != null) {
                if (!previous.entry().equals(entry)) {
                    throw new IOException("Conflicting physical pack entry");
                }
                return;
            }
            if (entry.baseOffset().isPresent() && !entries.containsKey(entry.baseOffset().getAsLong())) {
                throw new IOException("Offset delta base is not a registered pack entry");
            }
            entries.put(entry.offset(), encode(new Record(entry, null, null, -1)));
            unresolved.put(entry.offset(), 0L);
            if (entry.baseId().isPresent()) {
                waitingIds.put(idPrefix(entry.baseId().orElseThrow()) + entry.offset(), entry.offset());
            } else if (entry.baseOffset().isPresent()) {
                waitingOffsets.put(offsetPrefix(entry.baseOffset().getAsLong()) + entry.offset(), entry.offset());
            }
            commitBatch();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public void addObject(PackObjectParser.Entry entry, ObjectId id, ObjectType type, long size)
            throws IOException {
        requireOpen();
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        try {
            requireMutable();
            Record previous = record(entry.offset());
            if (previous == null || !previous.entry().equals(entry)) {
                throw new IOException("Object completion requires the registered physical entry");
            }
            validateObject(entry, type, size);
            if (previous.objectId() != null) {
                if (!id.equals(previous.objectId()) || type != previous.type() || size != previous.size()) {
                    throw new IOException("Conflicting pack object completion");
                }
                return;
            }
            Long existingOffset = objects.get(id.toHex());
            if (existingOffset != null) {
                Record existing = record(existingOffset);
                if (existing == null || !id.equals(existing.objectId())
                        || existing.type() != type || existing.size() != size) {
                    throw new IOException("Conflicting metadata for the same object ID");
                }
            }
            entries.put(entry.offset(), encode(new Record(entry, id, type, size)));
            if (existingOffset == null) {
                objects.put(id.toHex(), entry.offset());
            }
            unresolved.remove(entry.offset());
            if (entry.baseId().isPresent()) {
                waitingIds.remove(idPrefix(entry.baseId().orElseThrow()) + entry.offset());
            } else if (entry.baseOffset().isPresent()) {
                waitingOffsets.remove(offsetPrefix(entry.baseOffset().getAsLong()) + entry.offset());
            }
            commitBatch();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    private void inspectChain(long start, MVMap<Long, Long> visited) throws IOException {
        if (visited.containsKey(start)) {
            return;
        }
        long offset = start;
        while (true) {
            Long previous = visited.get(offset);
            if (previous != null) {
                if (previous == start) {
                    throw new IOException("Pack index contains a delta cycle requiring an external base");
                }
                return;
            }
            Record record = record(offset);
            if (record == null || record.objectId() == null) {
                throw new IOException("Pack index contains a missing or unresolved base record");
            }
            visited.put(offset, start);
            commitBatch();
            var entry = record.entry();
            if (entry.baseId().isPresent()) {
                String base = entry.baseId().orElseThrow().toHex();
                Long baseOffset = objects.get(base);
                if (baseOffset == null) {
                    externalBases.put(base, 0L);
                    commitBatch();
                    return;
                }
                offset = baseOffset;
            } else if (entry.baseOffset().isPresent()) {
                offset = entry.baseOffset().getAsLong();
            } else {
                return;
            }
        }
    }

    private void commitBatch() {
        if (++pendingChanges == 256) {
            store.commit();
            pendingChanges = 0;
        }
    }

    @Override
    public Optional<PackObjectParser.Entry> find(ObjectId id) throws IOException {
        requireOpen();
        Objects.requireNonNull(id, "id");
        try {
            Long offset = objects.get(id.toHex());
            if (offset == null) {
                return Optional.empty();
            }
            Record record = record(offset);
            if (record == null || !id.equals(record.objectId())) {
                throw new IOException("Invalid object lookup in pack index");
            }
            return Optional.of(record.entry());
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public Optional<PackObjectParser.Entry> find(long offset) throws IOException {
        requireOpen();
        try {
            Record record = record(offset);
            return record == null ? Optional.empty() : Optional.of(record.entry());
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public Optional<PackObjectParser.Entry> waitingFor(ObjectId id, long offset) throws IOException {
        requireOpen();
        Objects.requireNonNull(id, "id");
        try {
            var byId = waiting(waitingIds, idPrefix(id));
            return byId.isPresent() ? byId : waiting(waitingOffsets, offsetPrefix(offset));
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public boolean hasUnresolved() throws IOException {
        requireOpen();
        try {
            return !unresolved.isEmpty() || !waitingIds.isEmpty() || !waitingOffsets.isEmpty();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public List<ObjectId> externalBaseIds() throws IOException {
        requireOpen();
        try {
            if (!finalized()) {
                throw new IllegalStateException("Pack index is not finalized");
            }
            var result = new ArrayList<ObjectId>();
            var keys = externalBases.keyIterator(null);
            while (keys.hasNext()) {
                result.add(new ObjectId(keys.next()));
            }
            return List.copyOf(result);
        } catch (MVStoreException error) {
            throw storageFailure(error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid external base ID in pack index", error);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            store.close();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    private Optional<PackObjectParser.Entry> waiting(MVMap<String, Long> map, String prefix)
            throws IOException {
        String key = map.ceilingKey(prefix);
        if (key == null || !key.startsWith(prefix)) {
            return Optional.empty();
        }
        long offset = map.get(key);
        Record record = record(offset);
        if (record == null || record.objectId() != null || !unresolved.containsKey(offset)) {
            throw new IOException("Invalid waiting dependency in pack index");
        }
        var entry = record.entry();
        String expected;
        if (entry.baseId().isPresent()) {
            expected = idPrefix(entry.baseId().orElseThrow());
        } else if (entry.baseOffset().isPresent()) {
            expected = offsetPrefix(entry.baseOffset().getAsLong());
        } else {
            throw new IOException("Waiting pack entry has no delta base");
        }
        if (!key.equals(expected + offset)) {
            throw new IOException("Waiting dependency does not match its pack entry");
        }
        return Optional.of(entry);
    }

    private Record record(long offset) throws IOException {
        byte[] bytes = entries.get(offset);
        return bytes == null ? null : decode(offset, bytes);
    }

    private boolean finalized() throws IOException {
        Long value = state.get("finalized");
        if (value == null || value != 0 && value != 1) {
            throw new IOException("Invalid pack index finalization state");
        }
        return value == 1;
    }

    private void requireOpen() throws ClosedChannelException {
        if (store.isClosed()) {
            throw new ClosedChannelException();
        }
    }

    private void requireMutable() throws IOException {
        if (finalized()) {
            throw new IllegalStateException("Pack index is finalized");
        }
    }

    private IOException storageFailure(MVStoreException error) {
        var failure = new IOException("Pack index storage failure", error);
        closeFailed(store, failure);
        return failure;
    }

    private static void closeFailed(MVStore store, Throwable error) {
        try {
            store.closeImmediately();
        } catch (Throwable cleanup) {
            if (cleanup != error) {
                error.addSuppressed(cleanup);
            }
        }
    }

    private static void validateEntry(PackObjectParser.Entry entry) throws IOException {
        if (entry.offset() < 12 || entry.dataOffset() <= entry.offset() || entry.inflatedSize() < 0
                || entry.type() == null || entry.baseOffset() == null || entry.baseId() == null) {
            throw new IOException("Invalid physical pack entry metadata");
        }
        boolean valid = switch (entry.type()) {
            case OFS_DELTA -> entry.baseId().isEmpty() && entry.baseOffset().isPresent()
                    && entry.baseOffset().getAsLong() >= 12 && entry.baseOffset().getAsLong() < entry.offset()
                    && entry.dataOffset() - entry.offset() >= 2;
            case REF_DELTA -> entry.baseOffset().isEmpty() && entry.baseId().isPresent()
                    && entry.dataOffset() - entry.offset() >= 21;
            case COMMIT, TREE, BLOB, TAG -> entry.baseId().isEmpty() && entry.baseOffset().isEmpty();
        };
        if (!valid) {
            throw new IOException("Invalid pack entry base reference");
        }
    }

    private static void validateObject(PackObjectParser.Entry entry, ObjectType type, long size)
            throws IOException {
        if (size < 0 || type == ObjectType.OFS_DELTA || type == ObjectType.REF_DELTA) {
            throw new IOException("Object completion requires a logical type and non-negative size");
        }
        if (entry.type() != ObjectType.OFS_DELTA && entry.type() != ObjectType.REF_DELTA
                && (type != entry.type() || size != entry.inflatedSize())) {
            throw new IOException("Full object metadata differs from its physical pack entry");
        }
    }

    private static byte[] encode(Record record) {
        var entry = record.entry();
        ByteBuffer bytes = ByteBuffer.allocate(67);
        bytes.putLong(entry.dataOffset()).putLong(entry.inflatedSize()).put((byte) entry.type().code());
        if (entry.baseOffset().isPresent()) {
            bytes.putLong(entry.baseOffset().getAsLong());
        } else if (entry.baseId().isPresent()) {
            bytes.put(entry.baseId().orElseThrow().toBytes());
        }
        bytes.put((byte) (record.objectId() == null ? 0 : 1));
        if (record.objectId() != null) {
            bytes.put(record.objectId().toBytes()).put((byte) record.type().code()).putLong(record.size());
        }
        return Arrays.copyOf(bytes.array(), bytes.position());
    }

    private static Record decode(long offset, byte[] value) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        try {
            long dataOffset = bytes.getLong();
            long inflatedSize = bytes.getLong();
            ObjectType type = type(bytes.get());
            OptionalLong baseOffset = type == ObjectType.OFS_DELTA
                    ? OptionalLong.of(bytes.getLong()) : OptionalLong.empty();
            Optional<ObjectId> baseId = type == ObjectType.REF_DELTA
                    ? Optional.of(readId(bytes)) : Optional.empty();
            var entry = new PackObjectParser.Entry(offset, dataOffset, inflatedSize, type, baseOffset, baseId);
            validateEntry(entry);
            int resolved = bytes.get();
            if (resolved != 0 && resolved != 1) {
                throw new IOException("Invalid pack object resolution state");
            }
            ObjectId objectId = resolved == 1 ? readId(bytes) : null;
            ObjectType logicalType = resolved == 1 ? type(bytes.get()) : null;
            long logicalSize = resolved == 1 ? bytes.getLong() : -1;
            if (resolved == 1) {
                validateObject(entry, logicalType, logicalSize);
            }
            if (bytes.hasRemaining()) {
                throw new IOException("Unexpected bytes in pack index record");
            }
            return new Record(entry, objectId, logicalType, logicalSize);
        } catch (BufferUnderflowException error) {
            throw new IOException("Truncated pack index record", error);
        }
    }

    private static ObjectId readId(ByteBuffer bytes) {
        byte[] id = new byte[20];
        bytes.get(id);
        return new ObjectId(id);
    }

    private static ObjectType type(int code) throws IOException {
        return switch (code) {
            case 1 -> ObjectType.COMMIT;
            case 2 -> ObjectType.TREE;
            case 3 -> ObjectType.BLOB;
            case 4 -> ObjectType.TAG;
            case 6 -> ObjectType.OFS_DELTA;
            case 7 -> ObjectType.REF_DELTA;
            default -> throw new IOException("Invalid object type in pack index");
        };
    }

    private static String idPrefix(ObjectId id) {
        return id.toHex() + "/";
    }

    private static String offsetPrefix(long offset) {
        return offset + "/";
    }

    private record Record(PackObjectParser.Entry entry, ObjectId objectId, ObjectType type, long size) {
    }
}
