package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

public interface BufferedByteOutput {
    void write(ByteBuf buffer) throws IOException;

    void flush() throws IOException;
}
