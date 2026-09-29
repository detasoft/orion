package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackUploadIndex;
import pro.deta.orion.git.parser.v2.storage.shared.PackSupport;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class MemoryPackUploadIndex implements PackUploadIndex {
    private final MemoryIndexedPack data;
    private final Map<ObjectId, NavigableSet<Long>> waitingIds = new HashMap<>();
    private final Map<Long, NavigableSet<Long>> waitingOffsets = new HashMap<>();
    private final Set<ObjectId> bases = new TreeSet<>((left, right) ->
            Arrays.compareUnsigned(left.toBytes(), right.toBytes()));
    private long unresolved;
    private boolean finalized;
    private boolean closed;

    MemoryPackUploadIndex(MemoryIndexedPack data) throws IOException {
        this.data = data;
        Iterator<Long> offsets = data.offsets();
        while (offsets.hasNext()) {
            IndexedPack.Record record = data.record(offsets.next());
            if (record.objectId() == null) {
                register(record.entry());
            }
            record.entry().baseId().ifPresent(bases::add);
        }
    }

    private void register(IndexedPack.EntryMetadata entry) {
        unresolved++;
        if (entry.baseId().isPresent()) {
            ObjectId id = entry.baseId().orElseThrow();
            bases.add(id);
            waitingIds.computeIfAbsent(id, ignored -> new TreeSet<>()).add(entry.offset());
        } else if (entry.baseOffset().isPresent()) {
            waitingOffsets.computeIfAbsent(entry.baseOffset().getAsLong(), ignored -> new TreeSet<>())
                    .add(entry.offset());
        }
    }

    public void addEntry(IndexedPack.EntryMetadata entry) throws IOException {
        requireMutable();
        if (data.addEntry(entry.offset(), entry.dataOffset(), entry.inflatedSize(), entry.type(),
                entry.baseOffset(), entry.baseId())) {
            register(entry);
        }
    }

    public void addObject(IndexedPack.EntryMetadata entry, ObjectId id, GitObjectType type, long size)
            throws IOException {
        requireMutable();
        if (data.addObject(entry.offset(), id, type, size)) {
            unresolved--;
            IndexedPack.EntryMetadata stored = data.record(entry.offset()).entry();
            if (stored.baseId().isPresent()) {
                remove(waitingIds, stored.baseId().orElseThrow(), stored.offset());
            } else if (stored.baseOffset().isPresent()) {
                remove(waitingOffsets, stored.baseOffset().getAsLong(), stored.offset());
            }
        }
    }

    private static <K> void remove(Map<K, NavigableSet<Long>> waiting, K key, long offset) {
        NavigableSet<Long> offsets = waiting.get(key);
        if (offsets != null) {
            offsets.remove(offset);
            if (offsets.isEmpty()) {
                waiting.remove(key);
            }
        }
    }

    public Optional<IndexedPack.EntryMetadata> waitingFor(ObjectId id, long offset) throws IOException {
        requireOpen();
        Objects.requireNonNull(id, "id");
        NavigableSet<Long> entries = waitingIds.get(id);
        if (entries == null) {
            entries = waitingOffsets.get(offset);
        }
        return entries == null ? Optional.empty() : data.find(entries.first().longValue());
    }

    public boolean hasUnresolved() throws IOException {
        requireOpen();
        return unresolved != 0;
    }

    public Optional<ObjectId> nextExternalBase() throws IOException {
        requireOpen();
        if (hasUnresolved()) {
            throw new IOException("Cannot classify external bases before resolving all entries");
        }
        Iterator<ObjectId> iterator = bases.iterator();
        while (iterator.hasNext()) {
            ObjectId base = iterator.next();
            if (data.objectOffset(base) == null) {
                return Optional.of(base);
            }
            iterator.remove();
        }
        return Optional.empty();
    }

    public void finish() throws IOException {
        requireOpen();
        if (!finalized) {
            if (hasUnresolved()) {
                throw new IOException("Pack index contains unresolved records or dependencies");
            }
            if (nextExternalBase().isPresent()) {
                throw new IOException("Pack still requires an external base");
            }
            Map<Long, Long> visited = new HashMap<>();
            Iterator<Long> offsets = data.offsets();
            while (offsets.hasNext()) {
                PackSupport.inspectChain(data, visited, offsets.next(), () -> {});
            }
            data.flush();
            finalized = true;
            clear();
        }
    }

    private void requireOpen() throws IOException {
        data.requireOpen();
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    private void requireMutable() throws IOException {
        requireOpen();
        data.requireMutable();
        if (finalized) {
            throw new IllegalStateException("Pack index is finalized");
        }
    }

    private void clear() {
        waitingIds.clear();
        waitingOffsets.clear();
        bases.clear();
    }

    public void close() {
        closed = true;
        clear();
    }
}
