package pro.deta.orion.git.client.repository;

import io.netty.buffer.ByteBuf;

/**
 * Stages one pack independently from ref publication. The caller retains
 * ownership of buffers passed to {@link #write(ByteBuf)} and their reader
 * indexes must not change. {@link #complete()} validates, durably publishes,
 * and returns the pack id; closing before successful completion aborts the
 * staged pack. Completion and closing are idempotent after success.
 */
public interface GitPackWriter extends AutoCloseable {
    void write(ByteBuf chunk) throws GitRepositoryAccessException;

    GitPackId complete() throws GitRepositoryAccessException;

    @Override
    void close() throws GitRepositoryAccessException;
}
