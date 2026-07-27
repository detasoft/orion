package pro.deta.orion.git.client.repository;

import io.netty.buffer.ByteBuf;

public interface GitPackWriter extends AutoCloseable {
    void write(ByteBuf chunk) throws GitRepositoryAccessException;

    GitPackId complete() throws GitRepositoryAccessException;

    @Override
    void close() throws GitRepositoryAccessException;
}
