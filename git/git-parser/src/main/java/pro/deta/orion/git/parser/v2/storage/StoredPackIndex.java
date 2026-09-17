package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.ByteArrayDataType;
import org.h2.mvstore.type.LongDataType;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Permanent disk index: physical entries by offset and object IDs to offsets.
 * Populated in place during upload; no resolver work queues or finalization state are persisted here.
 * Storage must only open published files for reading. The writer is closed before its file is moved;
 * publication and directory durability belong to the storage owner. Each handle has a four-MiB page cache.
 */
final class StoredPackIndex implements AutoCloseable {
    private static final Set<String> MAP_NAMES = Set.of("entries", "objects");
    private final MVStore store;
    private final MVMap<Long, byte[]> entries;
    private final MVMap<ObjectId, Long> objects;
    private boolean writable;

    static StoredPackIndex create(Path path) throws IOException {
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

    static StoredPackIndex open(Path path) throws IOException {
        Files.size(path);
        return load(path, false);
    }

    private static StoredPackIndex load(Path path, boolean create) throws IOException {
        MVStore store = null;
        try {
            var builder = new MVStore.Builder().fileName(path.toAbsolutePath().toString())
                    .cacheSize(4).autoCommitDisabled().autoCommitBufferSize(0);
            if (!create) {
                builder.readOnly();
            }
            store = builder.open();
            if (!create && (store.getStoreVersion() != 2
                    || !store.getMapNames().equals(MAP_NAMES))) {
                throw new IOException("Unsupported pack index format");
            }
            var index = new StoredPackIndex(store, create);
            if (create) {
                store.setStoreVersion(2);
                store.commit();
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

    private StoredPackIndex(MVStore store, boolean writable) {
        this.store = store;
        this.writable = writable;
        entries = store.openMap("entries", new MVMap.Builder<Long, byte[]>()
                .keyType(LongDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
        objects = store.openMap("objects", new MVMap.Builder<ObjectId, Long>()
                .keyType(ObjectIdDataType.INSTANCE).valueType(LongDataType.INSTANCE));
    }

    boolean addEntry(PackObjectParser.Entry entry) throws IOException {
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
                return false;
            }
            if (entry.baseOffset().isPresent() && !entries.containsKey(entry.baseOffset().getAsLong())) {
                throw new IOException("Offset delta base is not a registered pack entry");
            }
            entries.put(entry.offset(), encode(new Record(entry, null, null, -1)));
            return true;
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    boolean addObject(PackObjectParser.Entry entry, ObjectId id, ObjectType type, long size)
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
                return false;
            }
            Long existingOffset = objects.get(id);
            if (existingOffset != null) {
                Record existing = record(existingOffset);
                if (existing == null || !id.equals(existing.objectId())
                        || existing.type() != type || existing.size() != size) {
                    throw new IOException("Conflicting metadata for the same object ID");
                }
            }
            entries.put(entry.offset(), encode(new Record(entry, id, type, size)));
            if (existingOffset == null) {
                objects.put(id, entry.offset());
            }
            return true;
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    Optional<PackObjectParser.Entry> find(ObjectId id) throws IOException {
        requireOpen();
        Objects.requireNonNull(id, "id");
        try {
            Long offset = objects.get(id);
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

    Optional<PackObjectParser.Entry> find(long offset) throws IOException {
        requireOpen();
        try {
            Record record = record(offset);
            return record == null ? Optional.empty() : Optional.of(record.entry());
        } catch (MVStoreException error) {
            throw storageFailure(error);
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

    Iterator<Long> offsets() {
        return entries.keyIterator(null);
    }

    long entryCount() {
        return entries.sizeAsLong();
    }

    long objectCount() {
        return objects.sizeAsLong();
    }

    Long objectOffset(ObjectId id) {
        return objects.get(id);
    }

    Record record(long offset) throws IOException {
        byte[] bytes = entries.get(offset);
        return bytes == null ? null : decode(offset, bytes);
    }

    void commit() {
        store.commit();
    }

    void finish() {
        store.commit();
        store.sync();
        writable = false;
    }

    boolean isOpen() {
        return !store.isClosed();
    }

    void requireOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    private void requireMutable() {
        if (!writable) {
            throw new IllegalStateException("Pack index is read-only");
        }
    }

    void abort(Throwable error) {
        closeFailed(store, error);
    }

    private IOException storageFailure(MVStoreException error) {
        var failure = new IOException("Pack index storage failure", error);
        abort(failure);
        return failure;
    }

    static void closeFailed(MVStore store, Throwable error) {
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

    record Record(PackObjectParser.Entry entry, ObjectId objectId, ObjectType type, long size) {
    }
}
