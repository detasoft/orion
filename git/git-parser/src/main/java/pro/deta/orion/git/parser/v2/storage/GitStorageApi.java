package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ExistsGitObjectRead;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

public final class GitStorageApi implements AutoCloseable {
    private final GitRefsStorage refs;
    private final GitPackStorage packs;

    public GitStorageApi(Path repository) throws IOException {
        packs = new GitPackStorage(Objects.requireNonNull(repository, "repository"));
        refs = new GitRefsStorage(repository);
    }

    public IndexedPack newPack() throws IOException {
        return packs.createPack();
    }

    public PackId persist(IndexedPack pack) throws IOException {
        return packs.persist(Objects.requireNonNull(pack, "pack"));
    }

    public <R> Optional<R> readObject(ObjectId objectId, GitObjectRead<R> reader) throws IOException {
        return packs.read(Objects.requireNonNull(objectId, "objectId"), Objects.requireNonNull(reader, "reader"));
    }

    public List<PackObjectLocation> locateObjects(Collection<ObjectId> objectIds) throws IOException {
        return packs.locate(Objects.requireNonNull(objectIds, "objectIds"));
    }

    public <R> R readObject(PackObjectLocation location, GitObjectRead<R> reader) throws IOException {
        return packs.read(Objects.requireNonNull(location, "location"), Objects.requireNonNull(reader, "reader"));
    }

    public Set<ObjectId> packObjectIds(PackId id) throws IOException {
        return packs.objectIds(Objects.requireNonNull(id, "packId"));
    }

    public boolean exists(ObjectId objectId) throws IOException {
        return readObject(objectId, new ExistsGitObjectRead()).isPresent();
    }

    public RefsSnapshot snapshotRefs() throws IOException {
        return refs.snapshot();
    }

    public void updateHead(Head head) throws IOException {
        Objects.requireNonNull(head, "head");
        if (head instanceof Head.Detached detached && !exists(new ObjectId(detached.target().toBytes()))) {
            throw new IOException("Detached HEAD object does not exist: " + detached.target());
        }
        refs.updateHead(head);
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
            List<RefUpdate> ready = new ArrayList<>(updates.size());
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (RefUpdate update : updates) {
                boolean missing = update.newId().isPresent() && !exists(update.newId().orElseThrow());
                results.add(new RefUpdateResult(update, missing ? OBJECT_NOT_FOUND : APPLIED, Optional.empty()));
                if (!missing) {
                    ready.add(update);
                }
            }
            if (atomic && ready.size() != updates.size()) {
                for (int index = 0; index < results.size(); index++) {
                    RefUpdateResult result = results.get(index);
                    if (result.status() == APPLIED) {
                        results.set(index, new RefUpdateResult(result.update(), ATOMIC_ABORTED, Optional.empty()));
                    }
                }
            } else {
                Iterator<RefUpdateResult> applied = refs.updateAll(ready, atomic).iterator();
                for (int index = 0; index < results.size(); index++) {
                    if (results.get(index).status() == APPLIED) {
                        results.set(index, applied.next());
                    }
                }
            }
            return List.copyOf(results);
        } catch (IOException error) {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (RefUpdate update : updates) {
                results.add(new RefUpdateResult(update, RefUpdateResult.Status.STORAGE_ERROR,
                        Optional.ofNullable(error.getMessage())));
            }
            return List.copyOf(results);
        }
    }

    /**
     * Scans published pack indexes for each requested object.
     * Returns object IDs mapped to lists of containing pack IDs; an object can occur in multiple packs.
     * Objects absent from published packs are omitted, but may still exist as loose objects.
     * Index read failures must be reported as errors, not treated as absent objects. Results are derived
     * from published per-pack indexes; a shared lookup index is not required.
     *
     * @param objectIds object IDs to locate, not pack IDs
     * @return containing pack IDs grouped by object ID
     * @throws IOException if a published pack or index cannot be read
     */
    public Map<ObjectId, List<PackId>> findPacksByObjectIds(Collection<ObjectId> objectIds) throws IOException {
        return packs.find(Objects.requireNonNull(objectIds, "objectIds"));
    }

    public GitStorageApi() {
        packs = new GitPackStorage();
        refs = new GitRefsStorage();
    }

    public List<PackId> packIds() throws IOException {
        return packs.ids();
    }

    public Optional<IndexedPack> openPack(PackId id) throws IOException {
        return packs.open(id);
    }

    @Override
    public void close() throws IOException {
        try {
            refs.close();
        } finally {
            packs.close();
        }
    }
}
