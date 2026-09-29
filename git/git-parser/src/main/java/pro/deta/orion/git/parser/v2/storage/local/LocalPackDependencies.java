package pro.deta.orion.git.parser.v2.storage.local;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.h2.mvstore.type.LongDataType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.storage.shared.PackSupport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

final class LocalPackDependencies implements AutoCloseable {
    private final LocalIndexedPack data;
    private final MVStore temporary;
    private final Path temporaryPath;
    private final MVMap<WaitingKey, Long> waiting;
    private final MVMap<Long, Long> visited;
    private final MVMap<ObjectId, Long> bases;
    private long unresolved;
    private int pendingChanges;
    private boolean temporaryDeleted;

    static LocalPackDependencies create(LocalIndexedPack data) throws IOException {
        Objects.requireNonNull(data, "data").requireMutable();
        Path temporaryPath = data.directory().resolve("data.tmv");
        MVStore temporary = null;
        Files.createFile(temporaryPath);
        try {
            MVStore.Builder builder = new MVStore.Builder()
                    .cacheSize(4).autoCommitDisabled().autoCommitBufferSize(0);
            builder.fileName(temporaryPath.toAbsolutePath().toString());
            temporary = builder.open();
            LocalPackDependencies state = new LocalPackDependencies(data, temporary, temporaryPath);
            Iterator<Long> offsets = data.offsets();
            while (offsets.hasNext()) {
                IndexedPack.Record record = data.record(offsets.next());
                IndexedPack.EntryMetadata entry = record.entry();
                if (record.objectId() == null) {
                    state.unresolved++;
                    WaitingKey key = waitingKey(entry);
                    if (key != null) {
                        state.waiting.put(key, entry.offset());
                    }
                }
                if (entry.baseId().isPresent()) {
                    state.bases.put(entry.baseId().orElseThrow(), 0L);
                }
                state.commitBatch();
            }
            temporary.commit();
            return state;
        } catch (IOException | RuntimeException | Error error) {
            if (temporary != null) {
                LocalIndexedPack.closeFailed(temporary, error);
            }
            deleteFailed(temporaryPath, error);
            if (error instanceof MVStoreException) {
                throw new IOException("Cannot create temporary pack state", error);
            }
            throw error;
        }
    }

    private LocalPackDependencies(LocalIndexedPack data, MVStore temporary, Path temporaryPath) {
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

    Optional<ObjectId> nextExternalBase() throws IOException {
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
        try {
            if (hasUnresolved()) {
                throw new IOException("Pack index contains unresolved records or dependencies");
            }
            if (nextExternalBase().isPresent()) {
                throw new IOException("Pack still requires an external base");
            }
            visited.clear();
            Iterator<Long> offsets = data.offsets();
            while (offsets.hasNext()) {
                PackSupport.inspectChain(data, visited, offsets.next(), this::commitBatch);
            }
            data.flush();
            discardTemporary();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    void entryAdded(IndexedPack.EntryMetadata entry) throws IOException {
        try {
            unresolved++;
            WaitingKey key = waitingKey(entry);
            if (key != null) {
                waiting.put(key, entry.offset());
                if (entry.baseId().isPresent()) {
                    bases.put(entry.baseId().orElseThrow(), 0L);
                }
            }
            commitBatch();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    void objectAdded(IndexedPack.EntryMetadata entry) throws IOException {
        try {
            unresolved--;
            WaitingKey key = waitingKey(entry);
            if (key != null) {
                waiting.remove(key);
            }
            commitBatch();
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    Optional<IndexedPack.EntryMetadata> waitingFor(ObjectId id, long offset) throws IOException {
        Objects.requireNonNull(id, "id");
        try {
            Optional<IndexedPack.EntryMetadata> byId = waiting(new WaitingKey(id, 0, 0));
            return byId.isPresent() ? byId : waiting(new WaitingKey(null, offset, 0));
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    boolean hasUnresolved() {
        return unresolved != 0;
    }

    @Override
    public void close() throws IOException {
        try {
            discardTemporary();
        } catch (MVStoreException error) {
            throw new IOException("Cannot close temporary pack state", error);
        }
    }

    private void discardTemporary() throws IOException {
        if (!temporaryDeleted) {
            temporary.closeImmediately();
            Files.deleteIfExists(temporaryPath);
            temporaryDeleted = true;
        }
    }

    private Optional<IndexedPack.EntryMetadata> waiting(WaitingKey prefix) throws IOException {
        WaitingKey key = waiting.ceilingKey(prefix);
        if (key == null || !key.sameBase(prefix)) {
            return Optional.empty();
        }
        IndexedPack.Record record = data.record(waiting.get(key));
        if (record == null || record.objectId() != null || !key.equals(waitingKey(record.entry()))) {
            throw new IOException("Invalid waiting dependency in pack index");
        }
        return Optional.of(record.entry());
    }

    private void commitBatch() {
        if (++pendingChanges == 256) {
            temporary.commit();
            pendingChanges = 0;
        }
    }

    private IOException storageFailure(Throwable error) {
        IOException failure = new IOException("Pack index storage failure", error);
        data.abort(failure);
        return failure;
    }

    private static void deleteFailed(Path path, Throwable error) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
        }
    }

    private static WaitingKey waitingKey(IndexedPack.EntryMetadata entry) {
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
