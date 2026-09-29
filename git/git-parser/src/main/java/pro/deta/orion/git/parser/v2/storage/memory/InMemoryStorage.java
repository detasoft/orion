package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackEntry;
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

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/** Owns published packs for one transient repository. */
public final class InMemoryStorage implements GitStorageApi {
    private final Map<PackChecksum, MemoryIndexedPack> packs = new LinkedHashMap<>();
    private boolean closed;

    public synchronized MutableIndexedPack newPack() throws IOException {
        requireOpen();
        return new MemoryIndexedPack();
    }

    public synchronized PackChecksum persist(MutableIndexedPack pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        if (!(pack instanceof MemoryIndexedPack memory)) {
            throw new IllegalArgumentException("Memory storage requires a memory pack");
        }
        PackChecksum id;
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

    private synchronized Map<PackChecksum, MemoryIndexedPack> packsSnapshot() throws IOException {
        requireOpen();
        return new LinkedHashMap<>(packs);
    }

    private synchronized MemoryIndexedPack pack(PackChecksum id) throws IOException {
        requireOpen();
        return packs.get(Objects.requireNonNull(id, "packId"));
    }

    public List<PackChecksum> packIds() throws IOException {
        return List.copyOf(packsSnapshot().keySet());
    }

    public Set<ObjectId> packObjectIds(PackChecksum id) throws IOException {
        MemoryIndexedPack pack = pack(id);
        return pack == null ? Set.of() : pack.objectIds();
    }

    public <R> Optional<R> readPack(PackChecksum id, GitPackRead<R> reader) throws IOException {
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
        for (Map.Entry<PackChecksum, MemoryIndexedPack> stored : packsSnapshot().entrySet()) {
            IndexedPack pack = stored.getValue();
            for (Map.Entry<ObjectId, PackObjectLocation> requested : found.entrySet()) {
                if (requested.getValue() != null) {
                    continue;
                }
                Optional<PackEntry> candidate = pack.find(requested.getKey());
                if (candidate.isPresent()) {
                    PackEntry entry = candidate.orElseThrow();
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

    public Map<ObjectId, List<PackChecksum>> findPacksByObjectIds(Collection<ObjectId> ids) throws IOException {
        Set<ObjectId> requested = new HashSet<>(ids);
        for (ObjectId id : requested) {
            Objects.requireNonNull(id, "objectId");
        }
        Map<ObjectId, List<PackChecksum>> result = new LinkedHashMap<>();
        for (Map.Entry<PackChecksum, MemoryIndexedPack> stored : packsSnapshot().entrySet()) {
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
    }
}
