package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Repository hooks used by Git commands. Fetch access receives already resolved wants and the same
 * refs snapshot used to resolve them. Checks must not mutate negotiation state or resolve names again;
 * the authorized object IDs remain the targets used to prepare the fetch response.
 */
public class GitRepositoryContext implements AutoCloseable {
    private final GitStorageApi storage;
    private final GitIndexAccess index;

    public GitRepositoryContext(GitStorageApi storage, GitIndexAccess index) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.index = Objects.requireNonNull(index, "index");
    }

    public final GitStorageApi storage() {
        return storage;
    }

    public final GitIndexAccess index() {
        return index;
    }

    @Override
    public void close() throws IOException {
        index.close();
    }

    public Optional<URI> packUri(PackChecksum id) {
        return Optional.empty();
    }

    public void checkFetchAccess(NegotiationContext context, RefsSnapshot snapshot) throws IOException {
    }

    public List<RefUpdateResult> publish(Optional<PackMetadata> pack, List<RefUpdate> updates, boolean atomic)
            throws IOException {
        if (pack.isPresent()) {
            PackMetadata metadata = pack.orElseThrow();
            if (!storage.exists(metadata.packId())) {
                throw new IOException("Cannot publish missing pack: " + metadata.packId());
            }
            index.publishIndex(metadata);
        }
        return publishRefs(storage, index, updates, atomic);
    }

    public static List<RefUpdateResult> publishRefs(GitStorageApi storage, GitIndexAccess index,
                                                    List<RefUpdate> updates, boolean atomic) {
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
                boolean missing = update.newId().isPresent() && !GitObjectRead.exists(storage, index, update.newId().orElseThrow());
                results.add(new RefUpdateResult(update, missing ? RefUpdateResult.Status.OBJECT_NOT_FOUND
                        : RefUpdateResult.Status.APPLIED, Optional.empty()));
                if (!missing) {
                    ready.add(update);
                }
            }
            if (atomic && ready.size() != updates.size()) {
                for (int position = 0; position < results.size(); position++) {
                    RefUpdateResult result = results.get(position);
                    if (result.status() == RefUpdateResult.Status.APPLIED) {
                        results.set(position, new RefUpdateResult(result.update(),
                                RefUpdateResult.Status.ATOMIC_ABORTED, Optional.empty()));
                    }
                }
            } else {
                Iterator<RefUpdateResult> applied = index.updateRefs(ready, atomic).iterator();
                for (int position = 0; position < results.size(); position++) {
                    if (results.get(position).status() == RefUpdateResult.Status.APPLIED) {
                        results.set(position, applied.next());
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
}
