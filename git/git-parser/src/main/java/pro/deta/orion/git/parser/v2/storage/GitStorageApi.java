package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ExistsGitObjectRead;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class GitStorageApi {
    private final GitObjectStorage objects;
    private final GitRefsStorage refs;
    private final GitPackStorage packs;

    public GitStorageApi(Path repository) throws IOException {
        packs = new GitPackStorage(Objects.requireNonNull(repository, "repository"));
        objects = new GitObjectStorage(packs);
        refs = new GitRefsStorage(repository, objects);
    }

    public IndexedPack newPack() throws IOException {
        return packs.createPack();
    }

    public PackId persist(IndexedPack pack) throws IOException {
        return packs.persist(Objects.requireNonNull(pack, "pack"));
    }

    public <R> Optional<R> readObject(ObjectId objectId, GitObjectRead<R> reader) throws IOException {
        return objects.read(Objects.requireNonNull(objectId, "objectId"), Objects.requireNonNull(reader, "reader"));
    }

    public boolean exists(ObjectId objectId) throws IOException {
        return readObject(objectId, new ExistsGitObjectRead()).isPresent();
    }

    public RefsSnapshot snapshotRefs() throws IOException {
        return refs.snapshot();
    }

    public void updateHead(Head head) throws IOException {
        refs.updateHead(head);
    }

    public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
        updates = List.copyOf(updates);
        try {
            return refs.updateAll(updates, atomic);
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
        return requirePacks().find(Objects.requireNonNull(objectIds, "objectIds"));
    }

    private GitPackStorage requirePacks() {
        if (packs == null) {
            throw new IllegalStateException("Persistent pack storage requires a repository path");
        }
        return packs;
    }
}
