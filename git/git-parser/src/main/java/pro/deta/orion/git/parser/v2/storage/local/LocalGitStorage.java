package pro.deta.orion.git.parser.v2.storage.local;

import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.read.ExistsGitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.PackObjectLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public final class LocalGitStorage implements GitStorageApi {
    private final GitPackStorage packs;

    public LocalGitStorage(Path repository) throws IOException {
        packs = new GitPackStorage(Objects.requireNonNull(repository, "repository"));
    }

    public LocalIndexedPack newPack() throws IOException {
        return packs.createPack();
    }

    public PackChecksum persist(MutableIndexedPack pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        if (!(pack instanceof LocalIndexedPack local)) {
            throw new IllegalArgumentException("Local storage requires a LocalIndexedPack");
        }
        return packs.persist(local);
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

    public Set<ObjectId> packObjectIds(PackChecksum id) throws IOException {
        return packs.objectIds(Objects.requireNonNull(id, "packId"));
    }

    public boolean exists(ObjectId objectId) throws IOException {
        return readObject(objectId, new ExistsGitObjectRead()).isPresent();
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
    public Map<ObjectId, List<PackChecksum>> findPacksByObjectIds(Collection<ObjectId> objectIds) throws IOException {
        return packs.find(Objects.requireNonNull(objectIds, "objectIds"));
    }

    public List<PackChecksum> packIds() throws IOException {
        return packs.ids();
    }

    public <R> Optional<R> readPack(PackChecksum id, GitPackRead<R> reader) throws IOException {
        return packs.readPack(Objects.requireNonNull(id, "packId"), Objects.requireNonNull(reader, "reader"));
    }

    @Override
    public void close() {}
}
