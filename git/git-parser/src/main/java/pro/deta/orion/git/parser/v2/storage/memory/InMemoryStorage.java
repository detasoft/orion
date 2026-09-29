package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.read.ExistsGitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.PackObjectLocation;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;
import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/** Owns published packs and atomic ref snapshots for one transient repository. */
public final class InMemoryStorage implements GitStorageApi {
    private final Map<PackId, MemoryIndexedPack> packs = new LinkedHashMap<>();
    private final Map<RefId, ObjectId> refs = new LinkedHashMap<>();
    private Head head = new Head.Symbolic(new RefId("refs/heads/main"));
    private boolean closed;

    public synchronized IndexedPack newPack() throws IOException {
        requireOpen();
        return new MemoryIndexedPack();
    }

    public synchronized PackId persist(IndexedPack pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        if (!(pack instanceof MemoryIndexedPack memory)) {
            throw new IllegalArgumentException("Memory storage requires a memory pack");
        }
        PackId id;
        try {
            id = memory.id();
        } catch (IOException failure) {
            closeUnreturned(memory, failure);
            throw failure;
        }
        MemoryIndexedPack existing = packs.get(id);
        if (existing == memory) {
            return id;
        }
        memory.requireMutable();
        try {
            requireOpen();
            if (existing == null) {
                memory.publish();
                packs.put(id, memory);
            } else {
                memory.discard();
            }
            return id;
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(memory, failure);
            throw failure;
        }
    }

    private synchronized Map<PackId, MemoryIndexedPack> packsSnapshot() throws IOException {
        requireOpen();
        return new LinkedHashMap<>(packs);
    }

    private synchronized MemoryIndexedPack pack(PackId id) throws IOException {
        requireOpen();
        return packs.get(Objects.requireNonNull(id, "packId"));
    }

    public List<PackId> packIds() throws IOException {
        return List.copyOf(packsSnapshot().keySet());
    }

    public Set<ObjectId> packObjectIds(PackId id) throws IOException {
        MemoryIndexedPack pack = pack(id);
        return pack == null ? Set.of() : pack.objectIds();
    }

    public <R> Optional<R> readPack(PackId id, GitPackRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        MemoryIndexedPack pack = pack(id);
        if (pack == null) {
            return Optional.empty();
        }
        R value = null;
        try (BufferedByteInputV2 input = pack.input()) {
            value = Objects.requireNonNull(reader.read(pack.size(), input), "reader result");
            return Optional.of(value);
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(value, failure);
            throw failure;
        }
    }

    public <R> Optional<R> readObject(ObjectId id, GitObjectRead<R> reader) throws IOException {
        Objects.requireNonNull(id, "objectId");
        Objects.requireNonNull(reader, "reader");
        List<PackObjectLocation> locations = locateObjects(List.of(id));
        return locations.isEmpty() ? Optional.empty() : Optional.of(readObject(locations.getFirst(), reader));
    }

    public <R> R readObject(PackObjectLocation location, GitObjectRead<R> reader) throws IOException {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(reader, "reader");
        MemoryIndexedPack pack = pack(location.packId());
        if (pack == null) {
            throw new IOException("Missing source pack: " + location.packId());
        }
        return pack.readObject(location.entry(), location.end(), location.baseId(), reader);
    }

    public List<PackObjectLocation> locateObjects(Collection<ObjectId> ids) throws IOException {
        Map<ObjectId, PackObjectLocation> found = new LinkedHashMap<>();
        for (ObjectId id : ids) {
            found.put(Objects.requireNonNull(id, "objectId"), null);
        }
        for (Map.Entry<PackId, MemoryIndexedPack> stored : packsSnapshot().entrySet()) {
            IndexedPack pack = stored.getValue();
            for (Map.Entry<ObjectId, PackObjectLocation> requested : found.entrySet()) {
                if (requested.getValue() != null) {
                    continue;
                }
                Optional<IndexedPack.EntryMetadata> candidate = pack.find(requested.getKey());
                if (candidate.isPresent()) {
                    IndexedPack.EntryMetadata entry = candidate.orElseThrow();
                    requested.setValue(new PackObjectLocation(requested.getKey(), stored.getKey(), entry,
                            pack.dataEnd(entry.offset()), pack.baseId(entry.offset())));
                }
            }
        }
        List<PackObjectLocation> result = new ArrayList<>();
        for (PackObjectLocation location : found.values()) {
            if (location != null) {
                result.add(location);
            }
        }
        return List.copyOf(result);
    }

    public Map<ObjectId, List<PackId>> findPacksByObjectIds(Collection<ObjectId> ids) throws IOException {
        Set<ObjectId> requested = new HashSet<>(ids);
        for (ObjectId id : requested) {
            Objects.requireNonNull(id, "objectId");
        }
        Map<ObjectId, List<PackId>> result = new LinkedHashMap<>();
        for (Map.Entry<PackId, MemoryIndexedPack> stored : packsSnapshot().entrySet()) {
            for (ObjectId id : requested) {
                if (stored.getValue().find(id).isPresent()) {
                    result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(stored.getKey());
                }
            }
        }
        return result;
    }

    public boolean exists(ObjectId id) throws IOException {
        return readObject(id, new ExistsGitObjectRead()).isPresent();
    }

    public synchronized RefsSnapshot snapshotRefs() throws IOException {
        requireOpen();
        return new RefsSnapshot(refs, head);
    }

    public void updateHead(Head value) throws IOException {
        Objects.requireNonNull(value, "head");
        if (value instanceof Head.Symbolic symbolic) {
            symbolic.target().requireFullName();
        } else if (value instanceof Head.Detached detached && !exists(new ObjectId(detached.target().toBytes()))) {
            throw new IOException("Detached HEAD object does not exist: " + detached.target());
        }
        synchronized (this) {
            requireOpen();
            head = value;
        }
    }

    public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
        updates = List.copyOf(updates);
        Set<RefId> names = new HashSet<>();
        for (RefUpdate update : updates) {
            update.ref().requireFullName();
            if (!names.add(update.ref())) {
                throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
            }
        }
        try {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            boolean missing = false;
            for (RefUpdate update : updates) {
                boolean absent = update.newId().isPresent() && !exists(update.newId().orElseThrow());
                results.add(new RefUpdateResult(update, absent ? OBJECT_NOT_FOUND : APPLIED, Optional.empty()));
                missing |= absent;
            }
            synchronized (this) {
                requireOpen();
                boolean failed = missing;
                if (!atomic || !missing) {
                    for (int index = 0; index < results.size(); index++) {
                        RefUpdateResult result = results.get(index);
                        RefUpdate update = result.update();
                        if (result.status() == APPLIED
                                && !Objects.equals(refs.get(update.ref()), update.expectedOld().orElse(null))) {
                            results.set(index, new RefUpdateResult(update, EXPECTED_OLD_MISMATCH, Optional.empty()));
                            failed = true;
                        }
                    }
                }
                for (int index = 0; index < results.size(); index++) {
                    RefUpdateResult result = results.get(index);
                    if (result.status() != APPLIED) {
                        continue;
                    }
                    RefUpdate update = result.update();
                    if (atomic && failed) {
                        results.set(index, new RefUpdateResult(update, ATOMIC_ABORTED, Optional.empty()));
                    } else if (update.newId().isPresent()) {
                        refs.put(update.ref(), update.newId().orElseThrow());
                    } else {
                        refs.remove(update.ref());
                    }
                }
            }
            return List.copyOf(results);
        } catch (IOException error) {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (RefUpdate update : updates) {
                results.add(new RefUpdateResult(update, STORAGE_ERROR, Optional.ofNullable(error.getMessage())));
            }
            return List.copyOf(results);
        }
    }

    private void requireOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    public synchronized void close() {
        closed = true;
        for (MemoryIndexedPack pack : packs.values()) {
            pack.close();
        }
        packs.clear();
        refs.clear();
    }
}
