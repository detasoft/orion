package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.IOException;

/**
 * Blocking buffered byte input.
 *
 * <p>Implementations may block while waiting for transport bytes. If
 * configured timeouts expire, read methods report that as {@link IOException}.
 * EOF before any byte is available may be reported as zero from
 * {@link #readInto(ByteBuf, int)}; EOF while satisfying exact reads must be
 * reported as {@link java.io.EOFException}.
 */
public interface BufferedByteInput {
    int available();

    int readUnsignedByte() throws IOException;

    ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException;

    /**
     * Reads up to {@code maxLength} bytes and returns zero when no byte is read.
     */
    int readInto(ByteBuf target, int maxLength) throws IOException;
}
