package pro.deta.orion.git.client.repository;

import io.netty.buffer.ByteBuf;

public interface GitPackReader extends AutoCloseable {
    ByteBuf read() throws GitRepositoryAccessException;

    @Override
    void close() throws GitRepositoryAccessException;
}
