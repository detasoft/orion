package pro.deta.orion.git.parser.v2.storage.shared;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One mutable pack's random-access bytes. Finish writing and flush before publishing its index.
 * Closing releases this handle and preserves stored bytes; it publishes neither the index nor refs.
 * The creating storage access closes any handle the caller has not already closed.
 */
public interface PackHandle extends AutoCloseable {
    int read(long offset, ByteBuffer target) throws IOException;

    void write(long offset, ByteBuffer source) throws IOException;

    long size() throws IOException;

    void truncate(long size) throws IOException;

    void flush() throws IOException;

    boolean isOpen();

    @Override
    void close() throws IOException;

}
