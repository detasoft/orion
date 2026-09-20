package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.fetch.FetchRequest;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.net.URI;
import pro.deta.orion.git.parser.v2.id.PackId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class GitRepositoryContext {
    private final GitStorageApi storage;

    public GitRepositoryContext(GitStorageApi storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public final GitStorageApi storage() {
        return storage;
    }

    public Optional<URI> packUri(PackId id) {
        return Optional.empty();
    }

    public void checkFetchAccess(FetchRequest request) throws IOException {
    }

    public List<RefUpdateResult> publish(Optional<IndexedPack> pack, List<RefUpdate> updates, boolean atomic)
            throws IOException {
        if (pack.isPresent()) {
            storage.persist(pack.orElseThrow());
        }
        return storage.updateRefs(updates, atomic);
    }
}
