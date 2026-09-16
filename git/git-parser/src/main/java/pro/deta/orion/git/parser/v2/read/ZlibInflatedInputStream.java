package pro.deta.orion.git.parser.v2.read;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Inflates a borrowed source bounded to one zlib stream using fixed-size compressed input storage.
 * Successful exhaustion validates the exact inflated length, zlib completion, and the source boundary.
 * Closing releases the inflater and scratch buffer without closing the borrowed source.
 */
final class ZlibInflatedInputStream extends InputStream {
    private final BufferedByteInput source;
    private final long expectedSize;
    private final Inflater inflater = new Inflater();
    private final ByteBuf compressed = Unpooled.buffer(8192, 8192);
    private final byte[] single = new byte[1];
    private long inflatedSize;
    private boolean exhausted;

    ZlibInflatedInputStream(BufferedByteInput source, long expectedSize) {
        this.source = source;
        this.expectedSize = expectedSize;
    }

    @Override
    public int read() throws IOException {
        return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        while (!exhausted) {
            if (inflater.finished()) {
                if (inflatedSize != expectedSize) {
                    throw new IOException("Inflated size differs from declared object size");
                }
                compressed.clear();
                if (inflater.getRemaining() != 0 || source.readInto(compressed, 1) != 0) {
                    throw new IOException("Unexpected bytes after the bounded zlib stream");
                }
                exhausted = true;
                return -1;
            }
            if (inflater.needsInput()) {
                compressed.clear();
                int count = source.readInto(compressed, compressed.writableBytes());
                if (count == 0) {
                    throw new EOFException("Truncated zlib stream");
                }
                inflater.setInput(compressed.array(), compressed.arrayOffset(), count);
            }
            int count;
            try {
                count = inflater.inflate(bytes, offset, length);
            } catch (DataFormatException error) {
                throw new IOException("Invalid zlib stream", error);
            }
            if (count > expectedSize - inflatedSize) {
                throw new IOException("Inflated size exceeds declared object size");
            }
            inflatedSize += count;
            if (count != 0) {
                return count;
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Zlib stream requires a dictionary");
            }
            if (!inflater.finished() && !inflater.needsInput()) {
                throw new IOException("Zlib stream made no progress");
            }
        }
        return -1;
    }

    @Override
    public void close() {
        inflater.end();
        compressed.release();
    }
}
