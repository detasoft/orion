package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.net.URI;
import pro.deta.orion.git.parser.v2.id.PackId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository hooks used by Git commands. Fetch access receives already resolved wants and the same
 * refs snapshot used to resolve them. Checks must not mutate negotiation state or resolve names again;
 * the authorized object IDs remain the targets used to prepare the fetch response.
 */
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

    public void checkFetchAccess(NegotiationContext context, RefsSnapshot snapshot) throws IOException {
    }

    public List<RefUpdateResult> publish(Optional<IndexedPack> pack, List<RefUpdate> updates, boolean atomic)
            throws IOException {
        if (pack.isPresent()) {
            storage.persist(pack.orElseThrow());
        }
        return storage.updateRefs(updates, atomic);
    }
}
