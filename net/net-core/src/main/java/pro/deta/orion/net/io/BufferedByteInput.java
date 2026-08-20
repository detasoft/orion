package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

public interface BufferedByteInput {
    int available();

    int readUnsignedByte() throws IOException;

    void skipBytes(int length) throws IOException;

    ByteBuf readCopy(int length) throws IOException;
}
