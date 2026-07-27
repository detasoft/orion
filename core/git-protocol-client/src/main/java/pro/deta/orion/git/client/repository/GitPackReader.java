package pro.deta.orion.git.client.repository;

import io.netty.buffer.ByteBuf;

/**
 * Reads one stored pack as caller-owned binary chunks. Each non-null buffer
 * returned by {@link #read()} must be released by the caller; {@code null}
 * marks the end of the pack. Closing is idempotent and releases only resources
 * owned by the reader.
 */
public interface GitPackReader extends AutoCloseable {
    ByteBuf read() throws GitRepositoryAccessException;

    @Override
    void close() throws GitRepositoryAccessException;
}
