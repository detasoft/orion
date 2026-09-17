package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.LongDataType;
import org.h2.mvstore.type.BasicDataType;
import org.h2.mvstore.WriteBuffer;
import java.nio.ByteBuffer;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackIndex;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Processing index for one upload. Permanent records are written directly into StoredPackIndex;
 * waiting dependencies, candidate external bases, and traversal marks live in a separate disposable disk store
 * with a four-MiB cache.
 * The unresolved counter and finalization flag belong only to this attempt; unfinished uploads are not resumed.
 * Finalization checks dependency chains, forces and freezes permanent data, then closes and removes temporary
 * state. Cycles are rejected until published storage can identify a usable external base that breaks them.
 * Closing releases both stores and deletes temporary state; the owner removes or moves the permanent file.
 * Methods are sequential. Storage failures close both handles; validation failures leave resolution retryable.
 */
final class FilePackIndex implements PackIndex, AutoCloseable {
    private final StoredPackIndex data;
    private final MVStore temporary;
    private final Path temporaryPath;
    private final MVMap<WaitingKey, Long> waiting;
    private final MVMap<Long, Long> visited;
    private final MVMap<ObjectId, Long> bases;
    private long unresolved;
    private int pendingChanges;
    private boolean finalized;
    private boolean temporaryDeleted;

    static FilePackIndex create(Path path, Path temporaryPath) throws IOException {
        Objects.requireNonNull(temporaryPath, "temporaryPath");
        var data = StoredPackIndex.create(path);
        MVStore temporary = null;
        boolean created = false;
        try {
            Files.createFile(temporaryPath);
            created = true;
            temporary = new MVStore.Builder().fileName(temporaryPath.toAbsolutePath().toString())
                    .cacheSize(4).autoCommitDisabled().autoCommitBufferSize(0).open();
            var index = new FilePackIndex(data, temporary, temporaryPath);
            temporary.commit();
            return index;
        } catch (IOException | RuntimeException | Error error) {
            data.abort(error);
            if (temporary != null) {
                StoredPackIndex.closeFailed(temporary, error);
            }
            if (created) {
                deleteFailed(temporaryPath, error);
            }
            deleteFailed(path, error);
            if (error instanceof MVStoreException) {
                throw new IOException("Cannot create temporary pack index", error);
            }
            throw error;
        }
    }

    private FilePackIndex(StoredPackIndex data, MVStore temporary, Path temporaryPath) {
        this.data = data;
        this.temporary = temporary;
        this.temporaryPath = temporaryPath;
        waiting = temporary.openMap("waiting", new MVMap.Builder<WaitingKey, Long>()
                .keyType(new WaitingKeyType()).valueType(LongDataType.INSTANCE));
        visited = temporary.openMap("visited", new MVMap.Builder<Long, Long>()
                .keyType(LongDataType.INSTANCE).valueType(LongDataType.INSTANCE));
        bases = temporary.openMap("bases", new MVMap.Builder<ObjectId, Long>()
                .keyType(ObjectIdDataType.INSTANCE).valueType(LongDataType.INSTANCE));
    }

    @Override
    public Optional<ObjectId> nextExternalBase() throws IOException {
        data.requireOpen();
        if (finalized) {
            return Optional.empty();
        }
        if (hasUnresolved()) {
            throw new IOException("Cannot classify external bases before resolving all entries");
        }
        try {
            ObjectId base;
            while ((base = bases.firstKey()) != null) {
                if (data.objectOffset(base) == null) {
                    return Optional.of(base);
                }
                bases.remove(base);
                commitBatch();
            }
            return Optional.empty();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    void finish() throws IOException {
        data.requireOpen();
        try {
            if (!finalized) {
                if (hasUnresolved()) {
                    throw new IOException("Pack index contains unresolved records or dependencies");
                }
                if (nextExternalBase().isPresent()) {
                    throw new IOException("Pack still requires an external base");
                }
                visited.clear();
                var offsets = data.offsets();
                while (offsets.hasNext()) {
                    inspectChain(offsets.next());
                }
                data.finish();
                finalized = true;
            }
            discardTemporary();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    long entryCount() throws IOException {
        data.requireOpen();
        try {
            return data.entryCount();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    long objectCount() throws IOException {
        data.requireOpen();
        try {
            return data.objectCount();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public void addEntry(PackObjectParser.Entry entry) throws IOException {
        requireMutable();
        try {
            if (data.addEntry(entry)) {
                unresolved++;
                WaitingKey key = waitingKey(entry);
                if (key != null) {
                    waiting.put(key, entry.offset());
                    if (entry.baseId().isPresent()) {
                        bases.put(entry.baseId().orElseThrow(), 0L);
                    }
                }
                commitBatch();
            }
        } catch (IOException | MVStoreException error) {
            if (error instanceof MVStoreException || !data.isOpen()) {
                throw storageFailure(error);
            }
            throw (IOException) error;
        }
    }

    @Override
    public void addObject(PackObjectParser.Entry entry, ObjectId id, ObjectType type, long size)
            throws IOException {
        requireMutable();
        try {
            if (data.addObject(entry, id, type, size)) {
                unresolved--;
                WaitingKey key = waitingKey(entry);
                if (key != null) {
                    waiting.remove(key);
                }
                commitBatch();
            }
        } catch (IOException | MVStoreException error) {
            if (error instanceof MVStoreException || !data.isOpen()) {
                throw storageFailure(error);
            }
            throw (IOException) error;
        }
    }

    @Override
    public Optional<PackObjectParser.Entry> find(ObjectId id) throws IOException {
        data.requireOpen();
        try {
            return data.find(id);
        } catch (IOException error) {
            throw data.isOpen() ? error : storageFailure(error);
        }
    }

    @Override
    public Optional<PackObjectParser.Entry> find(long offset) throws IOException {
        data.requireOpen();
        try {
            return data.find(offset);
        } catch (IOException error) {
            throw data.isOpen() ? error : storageFailure(error);
        }
    }

    @Override
    public Optional<PackObjectParser.Entry> waitingFor(ObjectId id, long offset) throws IOException {
        data.requireOpen();
        Objects.requireNonNull(id, "id");
        if (finalized) {
            return Optional.empty();
        }
        try {
            var byId = waiting(new WaitingKey(id, 0, 0));
            return byId.isPresent() ? byId : waiting(new WaitingKey(null, offset, 0));
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public boolean hasUnresolved() throws IOException {
        data.requireOpen();
        return unresolved != 0;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            data.close();
        } catch (IOException error) {
            failure = error;
        }
        try {
            discardTemporary();
        } catch (IOException | MVStoreException error) {
            IOException cleanup = error instanceof IOException io ? io : storageFailure(error);
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void discardTemporary() throws IOException {
        if (!temporaryDeleted) {
            temporary.closeImmediately();
            Files.deleteIfExists(temporaryPath);
            temporaryDeleted = true;
        }
    }

    private void inspectChain(long start) throws IOException {
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
            var record = data.record(offset);
            if (record == null || record.objectId() == null) {
                throw new IOException("Pack index contains a missing or unresolved base record");
            }
            visited.put(offset, start);
            commitBatch();
            var entry = record.entry();
            if (entry.baseId().isPresent()) {
                ObjectId base = entry.baseId().orElseThrow();
                Long baseOffset = data.objectOffset(base);
                if (baseOffset == null) {
                    throw new IOException("Pack still requires an external base");
                }
                offset = baseOffset;
            } else if (entry.baseOffset().isPresent()) {
                offset = entry.baseOffset().getAsLong();
            } else {
                return;
            }
        }
    }

    private Optional<PackObjectParser.Entry> waiting(WaitingKey prefix) throws IOException {
        WaitingKey key = waiting.ceilingKey(prefix);
        if (key == null || !key.sameBase(prefix)) {
            return Optional.empty();
        }
        var record = data.record(waiting.get(key));
        if (record == null || record.objectId() != null || !key.equals(waitingKey(record.entry()))) {
            throw new IOException("Invalid waiting dependency in pack index");
        }
        return Optional.of(record.entry());
    }

    private void commitBatch() {
        if (++pendingChanges == 256) {
            data.commit();
            temporary.commit();
            pendingChanges = 0;
        }
    }

    private void requireMutable() throws IOException {
        data.requireOpen();
        if (finalized) {
            throw new IllegalStateException("Pack index is finalized");
        }
    }

    private IOException storageFailure(Throwable error) {
        var failure = new IOException("Pack index storage failure", error);
        data.abort(failure);
        StoredPackIndex.closeFailed(temporary, failure);
        return failure;
    }

    private static void deleteFailed(Path path, Throwable error) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
        }
    }

    private static WaitingKey waitingKey(PackObjectParser.Entry entry) {
        if (entry.baseId().isPresent()) {
            return new WaitingKey(entry.baseId().orElseThrow(), 0, entry.offset());
        }
        if (entry.baseOffset().isPresent()) {
            return new WaitingKey(null, entry.baseOffset().getAsLong(), entry.offset());
        }
        return null;
    }

    private record WaitingKey(ObjectId baseId, long baseOffset, long entryOffset) {
        private boolean sameBase(WaitingKey other) {
            return Objects.equals(baseId, other.baseId) && baseOffset == other.baseOffset;
        }
    }

    private static final class WaitingKeyType extends BasicDataType<WaitingKey> {
        @Override
        public int compare(WaitingKey left, WaitingKey right) {
            int base;
            if (left.baseId() != null && right.baseId() != null) {
                base = ObjectIdDataType.INSTANCE.compare(left.baseId(), right.baseId());
            } else if (left.baseId() == null && right.baseId() == null) {
                base = Long.compare(left.baseOffset(), right.baseOffset());
            } else {
                return left.baseId() == null ? -1 : 1;
            }
            return base != 0 ? base : Long.compare(left.entryOffset(), right.entryOffset());
        }

        @Override
        public int getMemory(WaitingKey value) {
            return value.baseId() == null ? 40 : 104;
        }

        @Override
        public void write(WriteBuffer buffer, WaitingKey value) {
            buffer.put((byte) (value.baseId() == null ? 0 : 1));
            if (value.baseId() == null) {
                buffer.putLong(value.baseOffset());
            } else {
                ObjectIdDataType.INSTANCE.write(buffer, value.baseId());
            }
            buffer.putLong(value.entryOffset());
        }

        @Override
        public WaitingKey read(ByteBuffer buffer) {
            byte kind = buffer.get();
            if (kind == 0) {
                return new WaitingKey(null, buffer.getLong(), buffer.getLong());
            }
            if (kind != 1) {
                throw new IllegalStateException("Invalid waiting dependency kind");
            }
            return new WaitingKey(ObjectIdDataType.INSTANCE.read(buffer), 0, buffer.getLong());
        }

        @Override
        public WaitingKey[] createStorage(int size) {
            return new WaitingKey[size];
        }
    }
}
