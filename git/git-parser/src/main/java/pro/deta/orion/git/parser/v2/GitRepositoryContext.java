package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
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
    private final GitIndexApi indexApi;

    public GitRepositoryContext(GitStorageApi storage, GitIndexAccess index) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.index = Objects.requireNonNull(index, "index");
        this.indexApi = null;
    }

    public GitRepositoryContext(GitStorageApi storage, GitIndexApi index) throws IOException {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.indexApi = Objects.requireNonNull(index, "index");
        this.index = index.createAccess();
    }

    public final GitStorageApi storage() {
        return storage;
    }

    public final GitIndexAccess index() {
        return index;
    }

    @Override
    public void close() throws IOException {
        index.discard();
    }

    public Optional<URI> packUri(PackChecksum id) {
        return Optional.empty();
    }

    public void checkFetchAccess(NegotiationContext context, RefsSnapshot snapshot) throws IOException {
    }

    public List<RefUpdateResult> publish(Optional<PackMetadata> pack, List<RefUpdate> updates, boolean atomic)
            throws IOException {
        if (indexApi == null) {
            throw new IllegalStateException("Ref publication requires an index factory");
        }
        if (pack.isPresent()) {
            PackMetadata metadata = pack.orElseThrow();
            if (!storage.exists(metadata.packId())) {
                throw new IOException("Cannot publish missing pack: " + metadata.packId());
            }
            index.publishIndex(metadata);
        }
        return publishRefs(storage, indexApi, updates, atomic);
    }

    public static List<RefUpdateResult> publishRefs(GitStorageApi storage, GitIndexApi index,
                                                    List<RefUpdate> updates, boolean atomic) {
        List<RefUpdate> requested = List.copyOf(updates);
        Set<RefId> names = new HashSet<>();
        for (RefUpdate update : requested) {
            update.ref().requireFullName();
            if (!names.add(update.ref())) {
                throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
            }
        }
        try {
            return index.withAccess(reader -> {
                List<RefUpdate> ready = new ArrayList<>(requested.size());
                List<RefUpdateResult> results = new ArrayList<>(requested.size());
                for (RefUpdate update : requested) {
                    boolean missing = update.newId().isPresent()
                    && !GitObjectRead.exists(storage, reader, update.newId().orElseThrow());
                    results.add(new RefUpdateResult(update, missing ? RefUpdateResult.Status.OBJECT_NOT_FOUND
                            : RefUpdateResult.Status.APPLIED, Optional.empty()));
                    if (!missing) {
                        ready.add(update);
                    }
                }
                if (atomic && ready.size() != requested.size()) {
                    for (int position = 0; position < results.size(); position++) {
                        RefUpdateResult result = results.get(position);
                        if (result.status() == RefUpdateResult.Status.APPLIED) {
                            results.set(position, new RefUpdateResult(result.update(),
                                    RefUpdateResult.Status.ATOMIC_ABORTED, Optional.empty()));
                        }
                    }
                } else {
                    List<RefUpdateResult> committed = new ArrayList<>();
                    if (atomic) {
                        committed.addAll(applyRefs(index, ready));
                    } else {
                        for (RefUpdate update : ready) {
                            committed.addAll(applyRefs(index, List.of(update)));
                        }
                    }
                    Iterator<RefUpdateResult> applied = committed.iterator();
                    for (int position = 0; position < results.size(); position++) {
                        if (results.get(position).status() == RefUpdateResult.Status.APPLIED) {
                            results.set(position, applied.next());
                        }
                    }
                }
                return List.copyOf(results);
            });
        } catch (IOException error) {
            List<RefUpdateResult> results = new ArrayList<>(requested.size());
            for (RefUpdate update : requested) {
                results.add(new RefUpdateResult(update, RefUpdateResult.Status.STORAGE_ERROR,
                        Optional.ofNullable(error.getMessage())));
            }
            return List.copyOf(results);
        }
    }

    private static List<RefUpdateResult> applyRefs(GitIndexApi index, List<RefUpdate> updates) {
        if (updates.isEmpty()) {
            return List.of();
        }
        try {
            return index.withAccess(updates, access -> {
                access.apply();
                List<RefUpdateResult> results = new ArrayList<>(updates.size());
                for (RefUpdate update : updates) {
                    results.add(new RefUpdateResult(update, RefUpdateResult.Status.APPLIED, Optional.empty()));
                }
                return List.copyOf(results);
            });
        } catch (GitRefConflictException conflict) {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (RefUpdate update : updates) {
                results.add(new RefUpdateResult(update, update.ref().equals(conflict.update().ref())
                        ? RefUpdateResult.Status.EXPECTED_OLD_MISMATCH : RefUpdateResult.Status.ATOMIC_ABORTED,
                        Optional.of(conflict.getMessage())));
            }
            return List.copyOf(results);
        } catch (IOException failure) {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (RefUpdate update : updates) {
                results.add(new RefUpdateResult(update, RefUpdateResult.Status.STORAGE_ERROR,
                        Optional.ofNullable(failure.getMessage())));
            }
            return List.copyOf(results);
        }
    }
}
