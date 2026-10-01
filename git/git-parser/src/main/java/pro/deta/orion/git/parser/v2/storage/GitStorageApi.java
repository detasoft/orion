package pro.deta.orion.git.parser.v2.storage;

import java.io.IOException;

/**
 * Opens independent accesses to repository pack bytes. Closing the owner prevents new accesses;
 * existing accesses remain usable until applied or discarded. Bytes and index publication have
 * independent lifetimes.
 */
public interface GitStorageApi extends AutoCloseable {
    GitStorageAccess createAccess() throws IOException;

    default <T, E extends Exception> T withAccess(Operation<T, E> operation) throws IOException, E {
        GitStorageAccess access = createAccess();
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
        T run(GitStorageAccess access) throws IOException, E;
    }

    @Override
    void close() throws IOException;
}
