package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;

/**
 * A chunked Git protocol exchange. The caller retains ownership of buffers
 * passed to {@link #write(ByteBuf)} and their reader indexes must not change.
 * Buffers returned by {@link #read()} are owned and released by the caller;
 * {@code null} marks end of input. Closing is idempotent and implementations
 * must release their own resources on both success and failure.
 */
public interface GitProtocolSession extends AutoCloseable {
    void write(ByteBuf chunk) throws GitProtocolTransportException;

    ByteBuf read() throws GitProtocolTransportException;

    @Override
    void close() throws GitProtocolTransportException;
}
