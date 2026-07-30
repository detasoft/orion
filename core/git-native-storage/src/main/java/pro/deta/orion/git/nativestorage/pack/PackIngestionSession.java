package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;

public interface PackIngestionSession extends AutoCloseable {
    PackIngestionResult accept(ByteBuf input);

    PackIngestionResult endOfInput();

    @Override
    void close();
}
