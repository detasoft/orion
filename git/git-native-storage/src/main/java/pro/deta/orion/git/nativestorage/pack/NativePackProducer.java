package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;

public interface NativePackProducer extends AutoCloseable {
    Result produce(ByteBuf destination);

    @Override
    void close();

    enum Result {
        MORE,
        COMPLETED
    }
}
