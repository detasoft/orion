package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Opens independent accesses with a fixed set of writable refs and their original values.
 * Names capture current values without preparing changes; complete updates validate expected values
 * and prepare their targets immediately. An access without refs may modify HEAD; only an access
 * created for a pack may add objects or publish that pack's index.
 * Closing the owner prevents new accesses; existing accesses may still apply or discard.
 * Active accesses are exposed as a read-only snapshot for maintenance observation, not as a deletion lock.
 */
public interface GitIndexApi extends AutoCloseable {
    @Override
    void close() throws IOException;

    default GitIndexAccess createAccess() throws IOException {
        return createAccess(Set.of());
    }

    default GitIndexAccess createAccess(Optional<PackId> packId) throws IOException {
        return createAccess(Set.of(), packId);
    }

    GitIndexAccess createAccess(Set<RefId> refs) throws IOException;

    GitIndexAccess createAccess(Set<RefId> refs, Optional<PackId> packId) throws IOException;

    GitIndexAccess createAccess(List<RefUpdate> updates) throws IOException;

    GitIndexAccess createAccess(List<RefUpdate> updates, Optional<PackId> packId) throws IOException;

    GitHashAlgorithm hashAlgorithm();

    /** Reads the current symbolic or detached HEAD from the index. */
    default Head getHEAD() throws IOException {
        return withAccess(access -> access.snapshotRefs(new RefSelection.Head()).head());
    }

    Set<GitIndexAccess> activeAccesses();

    default <T, E extends Exception> T withAccess(Operation<T, E> operation) throws IOException, E {
        return withAccess(Set.of(), operation);
    }

    default <T, E extends Exception> T withAccess(Optional<PackId> packId, Operation<T, E> operation)
            throws IOException, E {
        return run(createAccess(packId), operation);
    }

    default <T, E extends Exception> T withAccess(Set<RefId> refs, Operation<T, E> operation)
            throws IOException, E {
        return run(createAccess(refs), operation);
    }

    default <T, E extends Exception> T withAccess(Set<RefId> refs, Optional<PackId> packId,
                                                  Operation<T, E> operation)
            throws IOException, E {
        return run(createAccess(refs, packId), operation);
    }

    default <T, E extends Exception> T withAccess(List<RefUpdate> updates, Operation<T, E> operation)
            throws IOException, E {
        return run(createAccess(updates), operation);
    }

    default <T, E extends Exception> T withAccess(List<RefUpdate> updates, Optional<PackId> packId,
                                                  Operation<T, E> operation) throws IOException, E {
        return run(createAccess(updates, packId), operation);
    }

    private static <T, E extends Exception> T run(GitIndexAccess access, Operation<T, E> operation)
            throws IOException, E {
        Throwable primary = null;
        try {
            return operation.run(access);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                access.discard();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (primary == null) throw cleanup;
                primary.addSuppressed(cleanup);
            }
        }
    }

    @FunctionalInterface
    interface Operation<T, E extends Exception> {
        T run(GitIndexAccess access) throws IOException, E;
    }
}
