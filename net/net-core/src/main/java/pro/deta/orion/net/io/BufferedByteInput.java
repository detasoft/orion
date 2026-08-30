package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

public interface BufferedByteInput {
    int available();

    int readUnsignedByte() throws IOException;

    ByteBuf readCopy(int length) throws IOException;

    /**
     * Reads up to {@code maxLength} bytes and returns zero when no byte is read.
     */
    int readInto(ByteBuf target, int maxLength) throws IOException;
}
