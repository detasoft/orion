package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class ZlibBoundaryByteSource implements BufferedByteInputV2.Source {
    private final BufferedByteInputV2 input;
    private final long expectedSize;
    private final Inflater inflater = new Inflater();
    private final byte[] inflated = new byte[8192];

    ZlibBoundaryByteSource(BufferedByteInputV2 input, long expectedSize) {
        this.input = Objects.requireNonNull(input, "input");
        this.expectedSize = expectedSize;
    }

    @Override
    public ByteBuffer read() throws IOException {
        if (inflater.finished()) {
            return null;
        }
        ByteBuffer compressed = input.buffer();
        if (compressed == null) {
            throw new EOFException("Truncated zlib stream");
        }
        int start = compressed.position();
        inflater.setInput(compressed);
        do {
            int previous = compressed.position();
            int count;
            try {
                count = inflater.inflate(inflated);
            } catch (DataFormatException error) {
                throw new IOException("Invalid pack zlib stream", error);
            }
            if (inflater.getBytesWritten() > expectedSize) {
                throw new IOException("Inflated size exceeds declared object size");
            }
            if (inflater.finished()) {
                if (inflater.getBytesWritten() != expectedSize) {
                    throw new IOException("Inflated size differs from declared object size");
                }
                break;
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Pack zlib stream requires a dictionary");
            }
            if (count == 0 && compressed.position() == previous && !inflater.needsInput()) {
                throw new IOException("Pack zlib stream made no progress");
            }
        } while (!inflater.needsInput());
        return compressed.slice(start, compressed.position() - start).asReadOnlyBuffer();
    }

    @Override
    public void release() {}

    @Override
    public void close() {
        inflater.end();
    }
}
