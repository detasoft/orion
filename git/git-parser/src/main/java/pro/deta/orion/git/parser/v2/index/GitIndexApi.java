package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Opens independent accesses with a fixed set of writable refs and their original values.
 * Names capture current values without preparing changes; complete updates validate expected values
 * and prepare their targets immediately. An access opened without refs can only modify objects and HEAD.
 * Closing the owner prevents new accesses; existing accesses may still apply or discard.
 */
public interface GitIndexApi extends AutoCloseable {
    @Override
    void close() throws IOException;

    default GitIndexAccess createAccess() throws IOException {
        return createAccess(Set.of());
    }

    GitIndexAccess createAccess(Set<RefId> refs) throws IOException;

    GitIndexAccess createAccess(List<RefUpdate> updates) throws IOException;

    GitHashAlgorithm hashAlgorithm();

    default <T, E extends Exception> T withAccess(Operation<T, E> operation) throws IOException, E {
        return withAccess(Set.of(), operation);
    }

    default <T, E extends Exception> T withAccess(Set<RefId> refs, Operation<T, E> operation)
            throws IOException, E {
        return run(createAccess(refs), operation);
    }

    default <T, E extends Exception> T withAccess(List<RefUpdate> updates, Operation<T, E> operation)
            throws IOException, E {
        return run(createAccess(updates), operation);
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
