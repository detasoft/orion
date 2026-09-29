package pro.deta.orion.git.parser.v2.storage.local;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.ByteArrayDataType;
import org.h2.mvstore.type.LongDataType;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.*;

public final class LocalIndexedPack implements MutableIndexedPack {
    private static final Set<String> MAP_NAMES = Set.of("entries", "objects");
    private final PackDataStorage bytes;
    private final MVStore store;
    private final MVMap<Long, byte[]> entries;
    private final MVMap<ObjectId, Long> objects;
    private final Path directory;
    private int pendingChanges;
    private PackId packId;
    private LocalPackDependencies dependencies;

    public static LocalIndexedPack create(Path directory) throws IOException {
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

    public static LocalIndexedPack open(Path packPath, Path indexPath) throws IOException {
        Files.size(indexPath);
        return load(packPath, indexPath, null);
    }

    private static LocalIndexedPack load(Path packPath, Path indexPath, Path directory) throws IOException {
        PackDataStorage bytes = directory == null
                ? FilePackDataStorage.open(packPath, StandardOpenOption.READ)
                : FilePackDataStorage.open(packPath, StandardOpenOption.CREATE_NEW,
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
            LocalIndexedPack pack = new LocalIndexedPack(bytes, store, directory);
            if (directory == null) {
                pack.packId = checksum(pack.bytes);
            } else {
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

    private LocalIndexedPack(PackDataStorage bytes, MVStore store, Path directory) {
        this.bytes = bytes;
        this.store = store;
        this.directory = directory;
        entries = store.openMap("entries", new MVMap.Builder<Long, byte[]>()
                .keyType(LongDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
        objects = store.openMap("objects", new MVMap.Builder<ObjectId, Long>()
                .keyType(ObjectIdDataType.INSTANCE).valueType(LongDataType.INSTANCE));
    }

    public Optional<ObjectId> nextExternalBase() throws IOException {
        return dependencies().nextExternalBase();
    }

    public Optional<EntryMetadata> waitingFor(ObjectId id, long offset) throws IOException {
        return dependencies().waitingFor(id, offset);
    }

    public boolean hasUnresolved() throws IOException {
        return dependencies().hasUnresolved();
    }

    private LocalPackDependencies dependencies() throws IOException {
        requireMutable();
        if (dependencies == null) {
            dependencies = LocalPackDependencies.create(this);
        }
        return dependencies;
    }

    public void append(ByteBuffer source) throws IOException {
        write(size(), source);
    }

    public void write(long offset, ByteBuffer source) throws IOException {
        requireMutable();
        if (offset < 0) {
            throw new IllegalArgumentException("Negative pack offset");
        }
        if (source.remaining() > Long.MAX_VALUE - offset) {
            throw new IOException("Pack size overflows a signed long");
        }
        packId = null;
        bytes.write(offset, source);
    }

    public int read(long offset, ByteBuffer target) throws IOException {
        requireOpen();
        return bytes.read(offset, target);
    }

    public long size() throws IOException {
        requireOpen();
        return bytes.size();
    }

    public void truncate(long size) throws IOException {
        requireMutable();
        packId = null;
        bytes.truncate(size);
    }

    public PackId id() throws IOException {
        requireOpen();
        if (packId == null) {
            throw new IOException("Pack checksum is not calculated");
        }
        return packId;
    }

    public void setId(PackId id) throws IOException {
        requireMutable();
        packId = Objects.requireNonNull(id, "id");
    }

    public PackId finish(long dataEnd) throws IOException {
        requireMutable();
        if (packId == null) {
            byte[] checksum = digest(bytes, dataEnd);
            write(dataEnd, ByteBuffer.wrap(checksum));
            packId = new PackId(checksum);
        }
        dependencies().finish();
        dependencies = null;
        return packId;
    }

    public boolean checksumMatches(PackId expected) throws IOException {
        return checksum(bytes).equals(expected)
                && MessageDigest.isEqual(digest(bytes, size() - 20), expected.toBytes());
    }

    public Path directory() {
        if (directory == null) {
            throw new IllegalStateException("Pack has no staging directory");
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

    public long dataEnd(long offset) throws IOException {
        requireOpen();
        Long next = entries.higherKey(offset);
        return next == null ? size() - 20 : next;
    }

    public Optional<ObjectId> baseId(long offset) throws IOException {
        requireOpen();
        Record record = record(offset);
        if (record == null) {
            throw new IOException("Unknown pack entry offset: " + offset);
        }
        EntryMetadata entry = record.entry();
        if (entry.type() != GitObjectType.OFS_DELTA) {
            return entry.baseId();
        }
        Record base = record(entry.baseOffset().orElseThrow());
        if (base == null || base.objectId() == null) {
            throw new IOException("Offset delta base has no ObjectId");
        }
        return Optional.of(base.objectId());
    }

    public static <R> R readObject(Path path, EntryMetadata entry, long end, Optional<ObjectId> baseId,
                                   GitObjectRead<R> reader) throws IOException {
        R value = null;
        try (PackDataStorage bytes = FilePackDataStorage.open(path, StandardOpenOption.READ)) {
            value = readBounded(bytes, entry, end, baseId, reader);
            return value;
        } catch (IOException | RuntimeException | Error error) {
            closeUnreturned(value, error);
            throw error;
        }
    }

    public <R> R readObject(EntryMetadata entry, long end, Optional<ObjectId> baseId,
                           GitObjectRead<R> reader) throws IOException {
        requireOpen();
        return readBounded(bytes, entry, end, baseId, reader);
    }

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
            if (dependencies != null) {
                dependencies.entryAdded(entry);
            }
            commitBatch();
            return true;
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

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
            if (dependencies != null) {
                dependencies.objectAdded(entry);
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
        try (bytes; LocalPackDependencies temporary = dependencies) {
            store.close();
        } catch (MVStoreException error) {
            throw new IOException("Cannot close pack index", error);
        }
    }

    public void discard() throws IOException {
        if (store.isReadOnly()) {
            throw new IllegalStateException("Pack is read-only");
        }
        close();
        if (directory != null) {
            Files.deleteIfExists(directory.resolve("data.mv"));
            Files.deleteIfExists(directory.resolve("data.pack"));
            Files.deleteIfExists(directory);
        }
    }

    public Iterator<Long> offsets() {
        return entries.keyIterator(null);
    }

    public long entryCount() {
        return entries.sizeAsLong();
    }

    public long objectCount() {
        return objects.sizeAsLong();
    }

    public Set<ObjectId> objectIds() {
        return Set.copyOf(objects.keySet());
    }

    public BufferedByteInputV2 input() throws IOException {
        requireOpen();
        return new BufferedByteInputV2(new PackByteSource(bytes, 0, size()));
    }

    public Long objectOffset(ObjectId id) {
        return objects.get(id);
    }

    public Record record(long offset) throws IOException {
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
            bytes.flush();
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

    public void requireMutable() throws IOException {
        requireOpen();
        if (store.isReadOnly()) {
            throw new IllegalStateException("Pack is read-only");
        }
    }

    void abort(Throwable error) {
        if (dependencies != null) {
            try {
                dependencies.close();
            } catch (Throwable cleanup) {
                error.addSuppressed(cleanup);
            }
        }
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

}
