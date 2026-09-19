package pro.deta.orion.git.parser.v2.pack;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.ByteArrayDataType;
import org.h2.mvstore.type.LongDataType;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.ObjectIdDataType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public final class IndexedPack implements PackTarget, AutoCloseable {
    private static final Set<String> MAP_NAMES = Set.of("entries", "objects");
    private final FileChannel bytes;
    private final MVStore store;
    private final MVMap<Long, byte[]> entries;
    private final MVMap<ObjectId, Long> objects;
    private final Path directory;
    private int pendingChanges;

    public static IndexedPack create(Path directory) throws IOException {
        Files.createDirectory(directory);
        try {
            Files.createFile(directory.resolve("data.mv"));
            return load(directory.resolve("data.pack"), directory.resolve("data.mv"), directory);
        } catch (IOException | RuntimeException | Error error) {
            try {
                Files.deleteIfExists(directory.resolve("data.mv"));
                Files.deleteIfExists(directory.resolve("data.pack"));
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    public static IndexedPack open(Path packPath, Path indexPath) throws IOException {
        Files.size(indexPath);
        return load(packPath, indexPath, null);
    }

    private static IndexedPack load(Path packPath, Path indexPath, Path directory) throws IOException {
        FileChannel bytes = directory == null
                ? FileChannel.open(packPath, StandardOpenOption.READ)
                : FileChannel.open(packPath, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
        MVStore store = null;
        try {
            MVStore.Builder builder = new MVStore.Builder().fileName(indexPath.toAbsolutePath().toString())
                    .cacheSize(4).autoCommitDisabled().autoCommitBufferSize(0);
            if (directory == null) {
                builder.readOnly();
            }
            store = builder.open();
            if (directory == null && (store.getStoreVersion() != 2 || !store.getMapNames().equals(MAP_NAMES))) {
                throw new IOException("Unsupported pack index format");
            }
            IndexedPack pack = new IndexedPack(bytes, store, directory);
            if (directory != null) {
                store.setStoreVersion(2);
                store.commit();
            }
            return pack;
        } catch (IOException | RuntimeException | Error error) {
            if (store != null) {
                closeFailed(store, error);
            }
            try {
                bytes.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            if (error instanceof MVStoreException) {
                throw new IOException("Cannot open pack index", error);
            }
            throw error;
        }
    }

    private IndexedPack(FileChannel bytes, MVStore store, Path directory) {
        this.bytes = bytes;
        this.store = store;
        this.directory = directory;
        entries = store.openMap("entries", new MVMap.Builder<Long, byte[]>()
                .keyType(LongDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
        objects = store.openMap("objects", new MVMap.Builder<ObjectId, Long>()
                .keyType(ObjectIdDataType.INSTANCE).valueType(LongDataType.INSTANCE));
    }

    @Override
    public void append(ByteBuffer source) throws IOException {
        write(size(), source);
    }

    public void write(long offset, ByteBuffer source) throws IOException {
        requireMutable();
        while (source.hasRemaining()) {
            int count = bytes.write(source, offset);
            if (count <= 0) {
                throw new IOException("Pack file write made no progress");
            }
            offset += count;
        }
    }

    public int read(long offset, ByteBuffer target) throws IOException {
        requireOpen();
        return bytes.read(target, offset);
    }

    public long size() throws IOException {
        requireOpen();
        return bytes.size();
    }

    public void truncate(long size) throws IOException {
        requireMutable();
        bytes.truncate(size);
    }

    public PackId id() throws IOException {
        long size = size();
        if (size < 32) {
            throw new EOFException("Truncated pack file");
        }
        ByteBuffer trailer = ByteBuffer.allocate(20);
        while (trailer.hasRemaining()) {
            int count = read(size - 20 + trailer.position(), trailer);
            if (count <= 0) {
                throw new EOFException("Truncated pack checksum");
            }
        }
        return new PackId(trailer.array());
    }

    public Path directory() {
        if (directory == null) {
            throw new IllegalStateException("Pack is read-only");
        }
        return directory;
    }

    public <R> R readObject(long offset, GitObjectRead<R> reader) throws IOException {
        EntryMetadata entry = find(offset).orElseThrow(() ->
                new IllegalArgumentException("Unknown pack entry offset: " + offset));
        return readStored(entry, bytes, size(), reader);
    }

    public <R> Optional<R> readObject(ObjectId id, GitObjectRead<R> reader) throws IOException {
        Optional<EntryMetadata> entry = find(id);
        return entry.isEmpty() ? Optional.empty()
                : Optional.of(readObject(entry.orElseThrow().offset(), reader));
    }

    public static <R> R readObject(Path path, EntryMetadata entry, GitObjectRead<R> reader) throws IOException {
        R value = null;
        try (FileChannel bytes = FileChannel.open(path, StandardOpenOption.READ)) {
            value = readStored(entry, bytes, bytes.size(), reader);
            return value;
        } catch (IOException | RuntimeException | Error error) {
            closeUnreturned(value, error);
            throw error;
        }
    }

    @Override
    public boolean addEntry(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                            OptionalLong baseOffset, Optional<ObjectId> baseId) throws IOException {
        requireOpen();
        EntryMetadata entry = new EntryMetadata(offset, dataOffset, inflatedSize, type, baseOffset, baseId);
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
            commitBatch();
            return true;
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public boolean addObject(long offset, ObjectId id, GitObjectType type, long size)
            throws IOException {
        requireOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        try {
            requireMutable();
            Record previous = record(offset);
            if (previous == null) {
                throw new IOException("Object completion requires the registered physical entry");
            }
            EntryMetadata entry = previous.entry();
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
            commitBatch();
            return true;
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    public Optional<EntryMetadata> find(ObjectId id) throws IOException {
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

    public Optional<EntryMetadata> find(long offset) throws IOException {
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
        try (bytes) {
            store.close();
        } catch (MVStoreException error) {
            throw new IOException("Cannot close pack index", error);
        }
    }

    @Override
    public void discard() throws IOException {
        Path staging = directory();
        close();
        Files.deleteIfExists(staging.resolve("data.mv"));
        Files.deleteIfExists(staging.resolve("data.pack"));
        Files.deleteIfExists(staging);
    }

    Iterator<Long> offsets() {
        return entries.keyIterator(null);
    }

    public long entryCount() {
        return entries.sizeAsLong();
    }

    public long objectCount() {
        return objects.sizeAsLong();
    }

    Long objectOffset(ObjectId id) {
        return objects.get(id);
    }

    Record record(long offset) throws IOException {
        byte[] value = entries.get(offset);
        return value == null ? null : decode(offset, value);
    }

    private void commitBatch() {
        if (++pendingChanges == 256) {
            store.commit();
            pendingChanges = 0;
        }
    }

    public void flush() throws IOException {
        requireMutable();
        try {
            bytes.force(true);
            store.commit();
            store.sync();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    boolean isOpen() {
        return bytes.isOpen() && !store.isClosed();
    }

    void requireOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    private void requireMutable() throws IOException {
        requireOpen();
        if (directory == null) {
            throw new IllegalStateException("Pack is read-only");
        }
    }

    void abort(Throwable error) {
        closeFailed(store, error);
        try {
            bytes.close();
        } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
        }
    }

    private IOException storageFailure(MVStoreException error) {
        IOException failure = new IOException("Pack index storage failure", error);
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

    private static <R> R readStored(EntryMetadata entry, FileChannel byteStore, long end,
                                   GitObjectRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        R value = null;
        try (ZlibBoundaryInputStream zlib = new ZlibBoundaryInputStream(new StoredInput(byteStore, entry.dataOffset(), end),
                entry.inflatedSize())) {
            value = Objects.requireNonNull(reader.read(entry.type(), entry.inflatedSize(), entry.baseId(),
                    new InputStreamBufferedByteInput(zlib)), "reader result");
            byte[] discard = new byte[8192];
            while (zlib.read(discard) != -1) {
            }
            return value;
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(value, failure);
            throw failure;
        }
    }

    private static void closeUnreturned(Object value, Throwable failure) {
        if (value instanceof AutoCloseable resource) {
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    private static final class StoredInput extends InputStream {
        private final ByteBuffer buffer = ByteBuffer.allocate(8192);
        private final FileChannel byteStore;
        private final long end;
        private long position;

        private StoredInput(FileChannel byteStore, long position, long end) {
            this.byteStore = byteStore;
            this.position = position;
            this.end = end;
            buffer.limit(0);
        }

        @Override
        public int read() throws IOException {
            if (!buffer.hasRemaining()) {
                if (position >= end) {
                    return -1;
                }
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), end - position));
                int count = byteStore.read(buffer, position);
                if (count == -1) {
                    return -1;
                }
                if (count == 0) {
                    throw new IOException("Pack byte store made no read progress");
                }
                position += count;
                buffer.flip();
            }
            return buffer.get() & 255;
        }
    }

    private static void validateEntry(EntryMetadata entry) throws IOException {
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

    private static void validateObject(EntryMetadata entry, GitObjectType type, long size)
            throws IOException {
        if (size < 0 || type == GitObjectType.OFS_DELTA || type == GitObjectType.REF_DELTA) {
            throw new IOException("Object completion requires a logical type and non-negative size");
        }
        if (entry.type() != GitObjectType.OFS_DELTA && entry.type() != GitObjectType.REF_DELTA
                && (type != entry.type() || size != entry.inflatedSize())) {
            throw new IOException("Full object metadata differs from its physical pack entry");
        }
    }

    private static byte[] encode(Record record) {
        EntryMetadata entry = record.entry();
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
            GitObjectType type = GitObjectType.valueOf(bytes.get());
            OptionalLong baseOffset = type == GitObjectType.OFS_DELTA
                    ? OptionalLong.of(bytes.getLong()) : OptionalLong.empty();
            Optional<ObjectId> baseId = type == GitObjectType.REF_DELTA
                    ? Optional.of(readId(bytes)) : Optional.empty();
            EntryMetadata entry = new EntryMetadata(offset, dataOffset, inflatedSize, type, baseOffset, baseId);
            validateEntry(entry);
            int resolved = bytes.get();
            if (resolved != 0 && resolved != 1) {
                throw new IOException("Invalid pack object resolution state");
            }
            ObjectId objectId = resolved == 1 ? readId(bytes) : null;
            GitObjectType logicalType = resolved == 1 ? GitObjectType.valueOf(bytes.get()) : null;
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

    public record EntryMetadata(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                                OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    record Record(EntryMetadata entry, ObjectId objectId, GitObjectType type, long size) {
    }
}
