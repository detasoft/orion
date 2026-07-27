package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;

public interface GitProtocolSession extends AutoCloseable {
    void write(ByteBuf chunk) throws GitProtocolTransportException;

    ByteBuf read() throws GitProtocolTransportException;

    @Override
    void close() throws GitProtocolTransportException;
}
