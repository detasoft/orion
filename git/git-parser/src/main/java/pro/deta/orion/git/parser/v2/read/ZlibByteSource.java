package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class ZlibByteSource implements BufferedByteInputV2.Source {
    private final BufferedByteInputV2 source;
    private final long expectedSize;
    private final Inflater inflater = new Inflater();
    private final ByteBuffer inflated = ByteBuffer.allocate(8192);
    private long inflatedSize;

    ZlibByteSource(BufferedByteInputV2 source, long expectedSize) {
        this.source = Objects.requireNonNull(source, "source");
        this.expectedSize = expectedSize;
    }

    @Override
    public ByteBuffer read() throws IOException {
        while (true) {
            if (inflater.finished()) {
                if (inflatedSize != expectedSize) {
                    throw new IOException("Inflated size differs from declared object size");
                }
                if (source.buffer() != null) {
                    throw new IOException("Unexpected bytes after the bounded zlib stream");
                }
                return null;
            }
            if (inflater.needsInput()) {
                ByteBuffer input = source.buffer();
                if (input == null) {
                    throw new EOFException("Truncated zlib stream");
                }
                inflater.setInput(input);
            }
            inflated.clear();
            int count;
            try {
                count = inflater.inflate(inflated);
            } catch (DataFormatException error) {
                throw new IOException("Invalid zlib stream", error);
            }
            if (count > expectedSize - inflatedSize) {
                throw new IOException("Inflated size exceeds declared object size");
            }
            inflatedSize += count;
            if (count != 0) {
                return inflated.flip();
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Zlib stream requires a dictionary");
            }
            if (!inflater.finished() && !inflater.needsInput()) {
                throw new IOException("Zlib stream made no progress");
            }
        }
    }

    @Override
    public void release() {}

    @Override
    public void close() {
        inflater.end();
    }
}
