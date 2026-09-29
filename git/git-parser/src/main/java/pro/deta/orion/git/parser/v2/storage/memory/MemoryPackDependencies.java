package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackEntry;
import pro.deta.orion.git.parser.v2.storage.shared.PackSupport;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class MemoryPackDependencies {
    private final MemoryIndexedPack data;
    private final Map<ObjectId, NavigableSet<Long>> waitingIds = new HashMap<>();
    private final Map<Long, NavigableSet<Long>> waitingOffsets = new HashMap<>();
    private final Set<ObjectId> bases = new TreeSet<>((left, right) ->
            Arrays.compareUnsigned(left.toBytes(), right.toBytes()));
    private long unresolved;

    MemoryPackDependencies(MemoryIndexedPack data) throws IOException {
        this.data = data;
        Iterator<Long> offsets = data.offsets();
        while (offsets.hasNext()) {
            IndexedPack.Record record = data.record(offsets.next());
            if (record.objectId() == null) {
                entryAdded(record.entry());
            }
            record.entry().baseId().ifPresent(bases::add);
        }
    }

    void entryAdded(PackEntry entry) {
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

    void objectAdded(PackEntry entry) {
        unresolved--;
        if (entry.baseId().isPresent()) {
            remove(waitingIds, entry.baseId().orElseThrow(), entry.offset());
        } else if (entry.baseOffset().isPresent()) {
            remove(waitingOffsets, entry.baseOffset().getAsLong(), entry.offset());
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

    Optional<PackEntry> waitingFor(ObjectId id, long offset) throws IOException {
        Objects.requireNonNull(id, "id");
        NavigableSet<Long> entries = waitingIds.get(id);
        if (entries == null) {
            entries = waitingOffsets.get(offset);
        }
        return entries == null ? Optional.empty() : data.find(entries.first().longValue());
    }

    boolean hasUnresolved() {
        return unresolved != 0;
    }

    Optional<ObjectId> nextExternalBase() throws IOException {
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

    void finish() throws IOException {
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
        close();
    }

    void close() {
        waitingIds.clear();
        waitingOffsets.clear();
        bases.clear();
    }
}
