package pro.deta.orion.git.parser.v2.storage;

import java.io.IOException;

/**
 * Opens independent accesses to repository pack bytes. Closing the owner prevents new accesses;
 * existing accesses remain usable until closed. Bytes and index publication have independent lifetimes.
 */
public interface GitStorageApi extends AutoCloseable {
    GitStorageAccess createAccess() throws IOException;

    @Override
    void close() throws IOException;
}
